package com.aquila.crawl;

import com.aquila.common.ApiException;
import com.aquila.sourcepolicy.Permission;
import com.aquila.sourcepolicy.SourcePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implements the collection commit protocol: a short transaction claims the source lease
 * and issues a fencing token and a unique run; then pages are fetched and parsed OUTSIDE
 * transactions, and each page is committed atomically with its raw records, durable PARSE
 * jobs, cursor CAS (guarded by the expected cursor version together with the lease owner
 * and fencing token issued to this run), and its coverage fact. A failing page keeps the
 * interval PARTIAL and does not advance the cursor.
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class CrawlApplicationService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SourcePolicy policy;
    private final SourceAdapter adapter;
    private final CoverageService coverage;
    private final ProcessingJobWorker worker;
    private final String leaseOwner = UUID.randomUUID().toString();
    private final Duration leaseDuration = Duration.ofMinutes(10);
    private final int pageSize = 50;
    private PageCommitHook pageCommitHook = PageCommitHook.NOOP;

    public CrawlApplicationService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            SourcePolicy policy,
            SourceAdapter adapter,
            CoverageService coverage,
            ProcessingJobWorker worker) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.policy = policy;
        this.adapter = adapter;
        this.coverage = coverage;
        this.worker = worker;
    }

    public void setPageCommitHook(PageCommitHook hook) {
        this.pageCommitHook = hook == null ? PageCommitHook.NOOP : hook;
    }

    public CrawlResult runCollection(
            String sourceCode, String subscriptionScope, Instant intervalStart, Instant intervalEnd) {
        SourcePolicy.SourcePermissionDecision decision = policy.require(Permission.ACCESS, sourceCode);
        policy.require(Permission.STORE_RAW, sourceCode);
        // PARSE jobs hand content to the normalization and analysis stages, so both
        // capabilities are re-checked here rather than only at scheduling time (§8.1).
        policy.require(Permission.STORE_NORMALIZED, sourceCode);
        policy.require(Permission.ANALYZE, sourceCode);

        int subscriptionVersion = jdbc.queryForObject(
                "SELECT subscription_version FROM m3_source_config WHERE source_code = ?",
                Integer.class, sourceCode);

        String runKey = runKey(sourceCode, intervalStart, intervalEnd);
        RunClaim claim = beginRun(sourceCode, runKey);
        if (claim == null) {
            String existing = jdbc.queryForObject(
                    "SELECT run_id FROM m3_task_instance WHERE source_code = ? AND run_key = ?",
                    String.class, sourceCode, runKey);
            return new CrawlResult(existing, coverage.computeOverall(existing));
        }
        String runId = claim.runId();

        String cursor = null;
        int pageIndex = 0;
        CoverageState lastState = null;
        while (true) {
            SourcePage page;
            try {
                page = adapter.fetch(new SourceFetchRequest(
                        sourceCode, subscriptionScope, intervalStart, intervalEnd,
                        cursor, pageIndex, pageSize));
            } catch (SourceFetchException exception) {
                lastState = coverage.commitPage(failedPage(
                        runId, sourceCode, subscriptionScope, subscriptionVersion,
                        intervalStart, intervalEnd, cursor, pageIndex,
                        decision.adapterContractVersion(),
                        "page fetch failed: " + exception.getMessage()));
                markRun(runId, "PARTIAL");
                break;
            }
            lastState = commitPage(claim, decision, sourceCode, subscriptionScope,
                    subscriptionVersion, intervalStart, intervalEnd, cursor, page, pageIndex);
            cursor = page.nextCursor();
            pageIndex++;
            if (page.terminal() || cursor == null) {
                markRun(runId, "COMPLETE".equals(lastState.overallStatus()) ? "COMPLETE" : "PARTIAL");
                break;
            }
        }
        return new CrawlResult(runId, lastState);
    }

    private RunClaim beginRun(String sourceCode, String runKey) {
        return transactions.execute(status -> {
            List<String> existing = jdbc.query(
                    "SELECT run_id FROM m3_task_instance WHERE source_code = ? AND run_key = ?",
                    (resultSet, rowNumber) -> resultSet.getString("run_id"), sourceCode, runKey);
            if (!existing.isEmpty()) {
                return null;
            }
            // The claim must be serialised: a non-locking read lets two workers compute the
            // same next fencing token, which destroys the monotonicity fencing relies on.
            List<LeaseRow> leases = jdbc.query(
                    "SELECT lease_owner, lease_until, fencing_token FROM m3_source_config WHERE source_code = ? FOR UPDATE",
                    (resultSet, rowNumber) -> new LeaseRow(
                            resultSet.getString("lease_owner"),
                            resultSet.getTimestamp("lease_until") == null
                                    ? null : resultSet.getTimestamp("lease_until").toInstant(),
                            resultSet.getLong("fencing_token")),
                    sourceCode);
            LeaseRow lease = leases.isEmpty() ? null : leases.get(0);
            Instant now = Instant.now();
            if (lease != null && lease.leaseUntil() != null
                    && now.isBefore(lease.leaseUntil())
                    && !leaseOwner.equals(lease.leaseOwner())) {
                throw new ApiException(HttpStatus.CONFLICT, "Source is leased by another worker");
            }
            long issuedToken = (lease == null ? 0 : lease.fencingToken()) + 1;
            int claimed = jdbc.update("""
                    UPDATE m3_source_config
                    SET lease_owner = ?, lease_until = ?, fencing_token = ?, updated_at = NOW(6)
                    WHERE source_code = ? AND fencing_token = ?
                    """, leaseOwner, now.plus(leaseDuration), issuedToken, sourceCode, issuedToken - 1);
            if (claimed != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "Source lease claim conflict");
            }
            String runId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO m3_task_instance
                        (run_id, source_code, run_key, attempt, state, cursor_before,
                         fencing_token, lease_owner, lease_until, created_at, updated_at)
                    VALUES (?, ?, ?, 1, 'RUNNING', NULL, ?, ?, ?, NOW(6), NOW(6))
                    """, runId, sourceCode, runKey, issuedToken, leaseOwner, now.plus(leaseDuration));
            return new RunClaim(runId, issuedToken, leaseOwner);
        });
    }

    private CoverageState commitPage(
            RunClaim claim, SourcePolicy.SourcePermissionDecision decision, String sourceCode,
            String subscriptionScope, int subscriptionVersion, Instant intervalStart, Instant intervalEnd,
            String cursorBefore, SourcePage page, int pageIndex) {
        String runId = claim.runId();
        return transactions.execute(status -> {
            List<String> rawIds = new ArrayList<>();
            for (RawRecord record : page.records()) {
                String hash = sha256(record.payload());
                jdbc.update("""
                        INSERT INTO m3_raw_record
                            (raw_record_id, source_code, outer_id, fetched_date, payload_hash,
                             license_version, parse_version, payload, received_at, created_at)
                        VALUES (?, ?, ?, CURDATE(), ?, ?, ?, ?, NOW(6), NOW(6))
                        ON DUPLICATE KEY UPDATE raw_record_id = raw_record_id
                        """, UUID.randomUUID().toString(), sourceCode, record.outerId(), hash,
                        decision.licenseVersion(), decision.adapterContractVersion(), record.payload());
                // The dedup key is the identity of the fact; on conflict the already persisted
                // reference is the only one a durable job may point at.
                rawIds.add(jdbc.queryForObject("""
                        SELECT raw_record_id FROM m3_raw_record
                        WHERE source_code = ? AND outer_id = ? AND fetched_date = CURDATE()
                          AND payload_hash = ?
                        """, String.class, sourceCode, record.outerId(), hash));
            }
            for (int index = 0; index < page.records().size(); index++) {
                String rawId = rawIds.get(index);
                String hash = sha256(page.records().get(index).payload());
                String outerId = page.records().get(index).outerId();
                String inputKey = sourceCode + "|" + outerId;
                jdbc.update("""
                        INSERT INTO m3_processing_job
                            (job_id, input_type, input_key, source_code, raw_record_id, fetched_date,
                             stage, processor_version, input_revision, state, fencing_token,
                             created_at, updated_at)
                        VALUES (?, 'RAW', ?, ?, ?, CURDATE(), 'PARSE', ?, ?, 'PENDING', 0, NOW(6), NOW(6))
                        ON DUPLICATE KEY UPDATE job_id = job_id
                        """, UUID.randomUUID().toString(), inputKey, sourceCode, rawId,
                        decision.adapterContractVersion(), hash);
            }
            jdbc.update("""
                    INSERT INTO m3_adapter_evidence
                        (evidence_id, run_id, source_code, page_index, http_status, content_type,
                         byte_size, terminal_evidence, adapter_contract_version, captured_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6))
                    """, UUID.randomUUID().toString(), runId, sourceCode, pageIndex,
                    page.httpStatus(), page.contentType(), page.byteSize(),
                    page.terminalEvidence(), page.adapterContractVersion());

            long expectedVersion = jdbc.queryForObject(
                    "SELECT cursor_version FROM m3_source_config WHERE source_code = ? FOR UPDATE",
                    Long.class, sourceCode);
            // The guard is the token and owner issued to THIS run, read before the row was
            // locked; reading them back from the locked row would make the predicate a tautology.
            int cursorUpdated = jdbc.update("""
                    UPDATE m3_source_config
                    SET cursor_version = cursor_version + 1, updated_at = NOW(6)
                    WHERE source_code = ? AND cursor_version = ?
                      AND fencing_token = ? AND lease_owner = ?
                    """, sourceCode, expectedVersion, claim.fencingToken(), claim.leaseOwner());
            if (cursorUpdated != 1) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Lease owner or fencing token no longer matches this run; page commit rejected");
            }
            jdbc.update(
                    "UPDATE m3_task_instance SET cursor_after = ?, updated_at = NOW(6) WHERE run_id = ?",
                    page.nextCursor(), runId);

            pageCommitHook.beforeCommit(pageIndex);

            return coverage.commitPage(new PageCommit(
                    runId, sourceCode, subscriptionScope, subscriptionVersion,
                    intervalStart, intervalEnd, cursorBefore, page.nextCursor(), pageIndex,
                    PageOutcome.SUCCESS, page.terminal(), page.unrecoverable(),
                    page.traceable(), page.adapterContractVersion(),
                    page.terminalEvidence(), null));
        });
    }

    private PageCommit failedPage(
            String runId, String sourceCode, String subscriptionScope, int subscriptionVersion,
            Instant intervalStart, Instant intervalEnd, String cursorBefore, int pageIndex,
            String adapterContractVersion, String reason) {
        return new PageCommit(runId, sourceCode, subscriptionScope, subscriptionVersion,
                intervalStart, intervalEnd, cursorBefore, null, pageIndex,
                PageOutcome.FAILURE, false, false, false, adapterContractVersion, null, reason);
    }

    private void markRun(String runId, String state) {
        jdbc.update(
                "UPDATE m3_task_instance SET state = ?, updated_at = NOW(6) WHERE run_id = ?",
                state, runId);
    }

    private static String runKey(String sourceCode, Instant start, Instant end) {
        return sourceCode + "|" + start + "|" + end;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte octet : digest) {
                builder.append(String.format("%02x", octet));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record LeaseRow(String leaseOwner, Instant leaseUntil, long fencingToken) {}

    /** Lease identity issued to one run; page commits must present it unchanged to succeed. */
    private record RunClaim(String runId, long fencingToken, String leaseOwner) {}
}
