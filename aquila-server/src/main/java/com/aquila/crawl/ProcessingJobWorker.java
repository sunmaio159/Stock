package com.aquila.crawl;

import com.aquila.common.ApiException;
import com.aquila.governance.DocRef;
import com.aquila.governance.LifecycleBlockedException;
import com.aquila.governance.LifecycleGuard;
import com.aquila.governance.RevisionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes durable PARSE jobs. After a page commit, jobs are left PENDING; a missed
 * in-memory notification is recovered by scanning the durable job table. A job is claimed
 * with a lease and a monotonically increasing fencing token; an expired lease can be
 * reclaimed by another worker, and the stale owner can no longer commit its outcome.
 *
 * <p>Stage C Task 2: for jobs carrying a document revision, the derived write is gated
 * by {@link LifecycleGuard} and a re-checked fencing token. A revoke or version change
 * between computation and commit turns the job into a non-retryable EXPIRED / REVOKED
 * outcome and leaves no derived content behind.
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class ProcessingJobWorker {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LifecycleGuard lifecycleGuard;
    private final RevisionService revisionService;
    private final String workerOwner = UUID.randomUUID().toString();
    private final Duration leaseDuration = Duration.ofMinutes(10);
    private Clock clock = Clock.systemUTC();

    /** Test seam: runs after computation and before the commit barrier, inside the worker transaction. */
    private volatile Runnable beforeCommitHook;

    public ProcessingJobWorker(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            LifecycleGuard lifecycleGuard,
            RevisionService revisionService) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.lifecycleGuard = lifecycleGuard;
        this.revisionService = revisionService;
    }

    /** Override the clock used for business timestamps; defaults to the system UTC clock. */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Test-only seam (owner decision D4): fires after the initial reads but before the
     * commit barrier, and only for jobs carrying a document revision. Production code
     * never installs a hook. Pass null to clear it. A hook simulating a concurrent
     * revoke must use a separate physical connection; see LifecycleRaceIntegrationTest.
     */
    public void setBeforeCommitHook(Runnable hook) {
        this.beforeCommitHook = hook;
    }

    /**
     * Scan PENDING jobs and lease-expired CLAIMED jobs; the recovery path for missed
     * notifications and for workers that died after claiming. Review round-1 I-1: one bad
     * job must not abort the batch, and an expired CLAIMED job must be reclaimable.
     */
    public int recover() {
        Instant now = clock.instant();
        List<String> candidates = jdbc.query("""
                SELECT job_id FROM m3_processing_job
                WHERE state = 'PENDING'
                   OR (state = 'CLAIMED' AND lease_until < ?)
                ORDER BY created_at, job_id
                """, (resultSet, rowNumber) -> resultSet.getString("job_id"), now);
        int processed = 0;
        for (String jobId : candidates) {
            try {
                JobLease lease = claim(jobId);
                if (lease != null && process(lease) != null) {
                    processed++;
                }
            } catch (LifecycleBlockedException blocked) {
                // Round-2 M-3: process() terminalises these internally. Should one ever
                // escape, never swallow it as a recoverable per-job failure.
                throw blocked;
            } catch (ApiException exception) {
                // A stale token, a missing raw record or a conflicting claim is a per-job
                // condition. Skipping it lets the rest of the batch recover; the job stays
                // for the next scan, and claim() already reclaims expired CLAIMED leases.
                // Round-2 M-1: make the skip observable instead of silently dropping it.
                jdbc.update("""
                        UPDATE m3_processing_job
                        SET last_error = LEFT(?, 512), retry_count = retry_count + 1,
                            updated_at = NOW(6)
                        WHERE job_id = ?
                        """, exception.getMessage(), jobId);
            }
        }
        return processed;
    }

    public JobLease claim(String jobId) {
        return transactions.execute(status -> {
            Instant now = clock.instant();
            int updated = jdbc.update("""
                    UPDATE m3_processing_job
                    SET state = 'CLAIMED', lease_owner = ?, lease_until = ?, fencing_token = fencing_token + 1,
                        updated_at = NOW(6)
                    WHERE job_id = ? AND (state = 'PENDING' OR (state = 'CLAIMED' AND lease_until < ?))
                    """, workerOwner, now.plus(leaseDuration), jobId, now);
            if (updated != 1) {
                return null;
            }
            long fencing = jdbc.queryForObject(
                    "SELECT fencing_token FROM m3_processing_job WHERE job_id = ?",
                    Long.class, jobId);
            return new JobLease(jobId, fencing);
        });
    }

    public JobOutcome process(JobLease lease) {
        try {
            return transactions.execute(status -> {
                // Plain (non-locking) read: a concurrent revoke must not be blocked by this
                // transaction's row lock. The barrier below re-reads with FOR UPDATE.
                List<JobRow> rows = jdbc.query("""
                        SELECT job_id, raw_record_id, doc_revision_id, captured_lifecycle_version
                        FROM m3_processing_job
                        WHERE job_id = ? AND state = 'CLAIMED' AND fencing_token = ?
                        """, (resultSet, rowNumber) -> new JobRow(
                                resultSet.getString(1), resultSet.getString(2),
                                resultSet.getString(3), resultSet.getObject(4, Long.class)),
                        lease.jobId(), lease.fencingToken());
                if (rows.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "Job is not claimable under this lease");
                }
                JobRow job = rows.get(0);
                if (job.rawRecordId() != null) {
                    int exists = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM m3_raw_record WHERE raw_record_id = ?",
                            Integer.class, job.rawRecordId());
                    if (exists == 0) {
                        throw new ApiException(HttpStatus.CONFLICT, "Referenced raw record is missing");
                    }
                }

                if (job.docRevisionId() != null) {
                    // D4: the seam is confined to document-revision jobs. Crawl jobs never
                    // invoke injected behaviour, so a stray hook cannot affect Task 1 paths.
                    Runnable hook = beforeCommitHook;
                    if (hook != null) {
                        hook.run();
                    }
                    assertLeaseCurrent(lease);
                    DocRef ref = resolveDocRef(job.docRevisionId());
                    // Review round-1 M-1: a NULL captured version must fail closed, not be
                    // silently coerced to 0 by ResultSet.getLong.
                    if (job.capturedLifecycleVersion() == null) {
                        throw new LifecycleBlockedException(
                                "REVOKED", "Job has no captured lifecycle version");
                    }
                    lifecycleGuard.assertCommittable(ref, job.capturedLifecycleVersion());
                    lifecycleGuard.assertContentUnchanged(ref.docRevisionId());
                    // Review round-1 I-3 / M-2: the CAS is a commit qualifier, not a
                    // side effect. A SUPERSEDED or already PROCESSED revision must not
                    // advance pointers or produce a second derived commit.
                    int transitioned = jdbc.update("""
                            UPDATE m4_doc_revision SET status = 'PROCESSED'
                            WHERE doc_revision_id = ? AND status = 'OBSERVED'
                            """, ref.docRevisionId());
                    if (transitioned != 1) {
                        throw new LifecycleBlockedException(
                                "REVOKED", "Revision is not in OBSERVED state");
                    }
                    revisionService.advanceProcessedPointer(ref.docId(), ref.revisionOrder());
                    jdbc.update("""
                            UPDATE m4_entity_mention SET is_dirty = FALSE
                            WHERE doc_revision_id = ?
                            """, ref.docRevisionId());
                    // D4: the marker is the authoritative recompute signal. Consume it in
                    // this same transaction as the dirty-flag clear; on a blocked commit
                    // the whole transaction rolls back and both stay outstanding.
                    jdbc.update("""
                            UPDATE m4_recompute_marker SET consumed_at = NOW(6)
                            WHERE doc_revision_id = ? AND consumed_at IS NULL
                            """, ref.docRevisionId());
                }

                int updated = jdbc.update("""
                        UPDATE m3_processing_job
                        SET state = 'COMPLETE', output_reference = ?, updated_at = NOW(6)
                        WHERE job_id = ? AND state = 'CLAIMED' AND fencing_token = ?
                        """, job.rawRecordId(), lease.jobId(), lease.fencingToken());
                if (updated != 1) {
                    throw new ApiException(HttpStatus.CONFLICT, "Job outcome commit rejected");
                }
                return new JobOutcome(lease.jobId(), "COMPLETE", job.rawRecordId());
            });
        } catch (LifecycleBlockedException blocked) {
            // The worker transaction has rolled back, so no derived write survives. Record
            // the terminal, non-retryable outcome outside it; the fencing predicate keeps a
            // stale worker from overwriting a job claimed by a newer owner.
            jdbc.update("""
                    UPDATE m3_processing_job
                    SET state = ?, last_error = ?, updated_at = NOW(6)
                    WHERE job_id = ? AND state = 'CLAIMED' AND fencing_token = ?
                    """, blocked.blockedState(), blocked.getMessage(),
                    lease.jobId(), lease.fencingToken());
            return new JobOutcome(lease.jobId(), blocked.blockedState(), null);
        }
    }

    /** Current read: sees a token bumped by another connection after this job was claimed. */
    private void assertLeaseCurrent(JobLease lease) {
        List<Long> tokens = jdbc.query("""
                SELECT fencing_token FROM m3_processing_job
                WHERE job_id = ? AND state = 'CLAIMED' FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getLong(1), lease.jobId());
        if (tokens.size() != 1 || tokens.get(0) != lease.fencingToken()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Stale fencing token; derived commit rejected");
        }
    }

    private DocRef resolveDocRef(String docRevisionId) {
        List<DocRef> refs = jdbc.query("""
                SELECT r.doc_id, r.doc_revision_id, d.source_code, r.revision_order
                FROM m4_doc_revision r
                JOIN m4_normalized_doc d ON d.doc_id = r.doc_id
                WHERE r.doc_revision_id = ?
                """, (resultSet, rowNumber) -> new DocRef(
                        resultSet.getString(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getLong(4)),
                docRevisionId);
        if (refs.isEmpty()) {
            // D4: V3's job table intentionally has no FK back to a V4 table (B1), so a
            // dangling doc_revision_id is possible. Treat it as a revoked input and take
            // the non-retryable terminal path instead of leaving the job stuck in CLAIMED.
            throw new LifecycleBlockedException("REVOKED", "Document revision is missing");
        }
        return refs.get(0);
    }

    private record JobRow(
            String jobId,
            String rawRecordId,
            String docRevisionId,
            Long capturedLifecycleVersion) {}
}
