package com.aquila.governance;

import com.aquila.common.ApiException;
import com.aquila.sourcepolicy.Permission;
import com.aquila.sourcepolicy.SourcePolicy;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Receives observed documents into an immutable per-document revision history.
 *
 * <p>One transaction upserts the document, allocates/validates the revision order,
 * inserts the immutable revision plus mentions and recompute markers, advances the
 * monotonic observed pointer and enqueues the durable PARSE job. Source-supplied
 * sequence is preferred; otherwise the local order is max(existing order, observed
 * pointer) + 1 under the document row lock.
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class RevisionService {

    private static final String MARKER_TYPE = "MENTION_CHANGED";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SourcePolicy policy;

    /**
     * Test seam (Stage C round-4 Minor-4): a hook invoked immediately after the document
     * row lock is taken inside receive(). It lets a test hold the first transaction at the
     * lock point until a second transaction has actually started, so the concurrent-ordering
     * IT cannot degrade into a sequential replay. Production never sets it.
     */
    private volatile Runnable afterDocumentLockHook;

    /**
     * Test seam (review round-4 I-2): invoked at the very start of the receive transaction,
     * before the doc upsert takes its row lock. A test can open a second transaction here
     * while the first still holds the lock, with NO ReadView fixed yet; this is the state in
     * which a "plain read before lock" regression becomes observable. Production never sets it.
     */
    private volatile Runnable beforeDocumentLockHook;

    public RevisionService(
            JdbcTemplate jdbc, TransactionTemplate transactions, SourcePolicy policy) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.policy = policy;
    }

    void setAfterDocumentLockHook(Runnable hook) {
        this.afterDocumentLockHook = hook;
    }

    void setBeforeDocumentLockHook(Runnable hook) {
        this.beforeDocumentLockHook = hook;
    }

    public DocumentRevision receive(ObservedDocument observed) {
        // Entry gate (unified design §8.1), before any write. It runs OUTSIDE the
        // transaction, so its plain SELECT does not create a ReadView for that transaction.
        // STORE_RAW belongs to the crawl stage, not to normalized ingestion.
        policy.require(Permission.ACCESS, observed.sourceCode());
        policy.require(Permission.STORE_NORMALIZED, observed.sourceCode());
        policy.require(Permission.ANALYZE, observed.sourceCode());

        return transactions.execute(status -> {
            // Review round-2 Important-1: the first statements in the transaction MUST be
            // current/locking reads. Under REPEATABLE READ a plain SELECT here would fix
            // the ReadView before the doc row lock is held, so a concurrent redelivery
            // cannot see the first transaction's row (verified on MySQL 8.4:
            // EmptyResultDataAccessException on the doc lookup). A concurrent receiver
            // blocks in upsertDocument until this one commits, so the ReadView created by
            // the re-check below is fresh.
            String docId = upsertDocument(observed);

            // Test seam: with the doc row lock held, let a test prove a second transaction
            // is already waiting before this one commits (no-op in production).
            Runnable hook = afterDocumentLockHook;
            if (hook != null) {
                hook.run();
            }

            // Re-check the capabilities this write consumes, at the point of use, after
            // the doc row lock is held; the commit barrier re-checks them once more before
            // any derived write.
            policy.require(Permission.STORE_NORMALIZED, observed.sourceCode());
            policy.require(Permission.ANALYZE, observed.sourceCode());

            List<DocPointer> pointers = jdbc.query("""
                    SELECT latest_observed_revision_order, lifecycle_version
                    FROM m4_normalized_doc
                    WHERE doc_id = ? FOR UPDATE
                    """, (resultSet, rowNumber) ->
                            new DocPointer(resultSet.getLong(1), resultSet.getLong(2)),
                    docId);
            DocPointer pointer = pointers.get(0);

            // Review round-1 I-4: at-least-once delivery is a pipeline assumption. With
            // no source sequence, the same (raw record, normalizer version, body hash)
            // is the same observation; short-circuit before allocating a fresh local
            // order so a redelivery is a no-op rather than a new revision + job. The
            // doc row lock above serialises this check with concurrent receives.
            if (observed.sourceRevisionOrder() == null) {
                List<LocalRedelivery> redelivered = jdbc.query("""
                        SELECT doc_revision_id, revision_order
                        FROM m4_doc_revision
                        WHERE doc_id = ? AND raw_record_id = ?
                          AND normalizer_version = ? AND parser_version = ? AND body_hash = ?
                        """, (resultSet, rowNumber) -> new LocalRedelivery(
                                resultSet.getString(1), resultSet.getLong(2)),
                        docId, observed.rawRecordId(), observed.normalizerVersion(),
                        observed.parserVersion(), observed.bodyHash());
                if (!redelivered.isEmpty()) {
                    LocalRedelivery row = redelivered.get(0);
                    return new DocumentRevision(docId, row.docRevisionId(),
                            observed.sourceCode(), observed.outerId(), row.revisionOrder(),
                            pointer.latestObserved());
                }
            }

            long order = observed.sourceRevisionOrder() != null
                    ? observed.sourceRevisionOrder()
                    : allocateLocalOrder(docId, pointer.latestObserved());
            if (order <= 0) {
                throw new ApiException(HttpStatus.CONFLICT, "Revision order must be positive");
            }

            List<ExistingRevision> existing = jdbc.query("""
                    SELECT doc_revision_id, body_hash
                    FROM m4_doc_revision
                    WHERE doc_id = ? AND revision_order = ?
                    """, (resultSet, rowNumber) ->
                            new ExistingRevision(resultSet.getString(1), resultSet.getString(2)),
                    docId, order);
            if (!existing.isEmpty()) {
                // Idempotent redelivery. A source order reused with different content is a
                // collision, not an update: revision content is immutable.
                if (!existing.get(0).bodyHash().equals(observed.bodyHash())) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Revision order reused with different content");
                }
                return new DocumentRevision(docId, existing.get(0).docRevisionId(),
                        observed.sourceCode(), observed.outerId(), order,
                        pointer.latestObserved());
            }

            String revisionId = UUID.randomUUID().toString();
            insertRevision(observed, docId, revisionId, order);
            insertQuality(revisionId);
            for (ObservedDocument.Mention mention : observed.mentions()) {
                insertMention(revisionId, mention);
                insertRecomputeMarker(revisionId, mention.securityCode());
            }

            boolean advanced = doAdvanceObserved(docId, order);
            if (advanced) {
                jdbc.update("""
                        UPDATE m4_normalized_doc
                        SET current_doc_revision_id = ?, updated_at = NOW(6)
                        WHERE doc_id = ? AND latest_observed_revision_order = ?
                        """, revisionId, docId, order);
            }
            long observedOrder = Math.max(pointer.latestObserved(), order);
            enqueueParseJob(observed, revisionId, pointer.lifecycleVersion());
            return new DocumentRevision(docId, revisionId, observed.sourceCode(),
                    observed.outerId(), order, observedOrder);
        });
    }

    /** Monotone observed pointer. A late, lower order can never move it backwards. */
    public boolean advanceObservedPointer(String docId, long order) {
        return transactions.execute(status -> doAdvanceObserved(docId, order));
    }

    /** Monotone processed pointer. Rejects a late, lower order instead of rolling back. */
    public boolean advanceProcessedPointer(String docId, long order) {
        return transactions.execute(status -> doAdvanceProcessed(docId, order));
    }

    private boolean doAdvanceObserved(String docId, long order) {
        return jdbc.update("""
                UPDATE m4_normalized_doc
                SET latest_observed_revision_order = ?, updated_at = NOW(6)
                WHERE doc_id = ? AND latest_observed_revision_order < ?
                """, order, docId, order) == 1;
    }

    private boolean doAdvanceProcessed(String docId, long order) {
        return jdbc.update("""
                UPDATE m4_normalized_doc
                SET latest_processed_revision_order = ?, updated_at = NOW(6)
                WHERE doc_id = ? AND latest_processed_revision_order < ?
                """, order, docId, order) == 1;
    }

    private String upsertDocument(ObservedDocument observed) {
        // Still inside the transaction, but before any statement has fixed a ReadView.
        Runnable beforeLock = beforeDocumentLockHook;
        if (beforeLock != null) {
            beforeLock.run();
        }
        jdbc.update("""
                INSERT INTO m4_normalized_doc
                    (doc_id, source_code, outer_id, current_doc_revision_id,
                     latest_observed_revision_order, latest_processed_revision_order,
                     lifecycle_status, lifecycle_version, created_at, updated_at)
                VALUES (?, ?, ?, NULL, 0, 0, 'ACTIVE', 0, NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE doc_id = doc_id
                """, UUID.randomUUID().toString(), observed.sourceCode(), observed.outerId());
        return jdbc.queryForObject("""
                SELECT doc_id FROM m4_normalized_doc
                WHERE source_code = ? AND outer_id = ? FOR UPDATE
                """, String.class, observed.sourceCode(), observed.outerId());
    }

    private long allocateLocalOrder(String docId, long latestObserved) {
        Long highest = jdbc.queryForObject("""
                SELECT MAX(revision_order) FROM m4_doc_revision WHERE doc_id = ?
                """, Long.class, docId);
        return Math.max(latestObserved, highest == null ? 0L : highest) + 1;
    }

    private void insertRevision(
            ObservedDocument observed, String docId, String revisionId, long order) {
        jdbc.update("""
                INSERT INTO m4_doc_revision
                    (doc_revision_id, doc_id, revision_order, body_hash,
                     raw_record_id, fetched_date, published_at, observed_at,
                     normalizer_version, parser_version, status,
                     content_fingerprint, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OBSERVED', '', NOW(6))
                """, revisionId, docId, order, observed.bodyHash(), observed.rawRecordId(),
                Date.valueOf(observed.fetchedDate()),
                observed.publishedAt() == null ? null : Timestamp.from(observed.publishedAt()),
                Timestamp.from(observed.observedAt()),
                observed.normalizerVersion(), observed.parserVersion());
        // Compute the fingerprint from the STORED column values, not from the bind
        // parameters: DATETIME(6) formatting can differ between a JDBC Timestamp and the
        // stored column, which would make the guard report a false mismatch. Same
        // expression as LifecycleGuard.assertContentUnchanged.
        jdbc.update("""
                UPDATE m4_doc_revision
                SET content_fingerprint = SHA2(CONCAT_WS('|',
                        doc_id, revision_order, body_hash, raw_record_id, fetched_date,
                        published_at, observed_at, normalizer_version, parser_version), 256)
                WHERE doc_revision_id = ?
                """, revisionId);
    }

    private void insertQuality(String revisionId) {
        jdbc.update("""
                INSERT INTO m4_doc_quality
                    (doc_revision_id, is_duplicate, duplicate_of_revision_id,
                     quality_reason, created_at)
                VALUES (?, FALSE, NULL, NULL, NOW(6))
                """, revisionId);
    }

    private void insertMention(String revisionId, ObservedDocument.Mention mention) {
        jdbc.update("""
                INSERT INTO m4_entity_mention
                    (mention_id, doc_revision_id, security_code, mention_count,
                     is_dirty, created_at)
                VALUES (?, ?, ?, ?, TRUE, NOW(6))
                ON DUPLICATE KEY UPDATE mention_count = VALUES(mention_count), is_dirty = TRUE
                """, UUID.randomUUID().toString(), revisionId, mention.securityCode(),
                mention.mentionCount());
    }

    private void insertRecomputeMarker(String revisionId, String securityCode) {
        jdbc.update("""
                INSERT INTO m4_recompute_marker
                    (marker_id, security_code, marker_type, doc_revision_id, created_at)
                VALUES (?, ?, ?, ?, NOW(6))
                ON DUPLICATE KEY UPDATE created_at = NOW(6), consumed_at = NULL
                """, UUID.randomUUID().toString(), securityCode, MARKER_TYPE, revisionId);
    }

    private void enqueueParseJob(
            ObservedDocument observed, String revisionId, long capturedLifecycleVersion) {
        jdbc.update("""
                INSERT INTO m3_processing_job
                    (job_id, input_type, input_key, source_code, raw_record_id, fetched_date,
                     stage, processor_version, input_revision, state, fencing_token,
                     retry_count, created_at, updated_at,
                     doc_revision_id, captured_lifecycle_version)
                VALUES (?, 'DOC_REVISION', ?, ?, ?, ?, 'PARSE', ?, ?, 'PENDING', 0, 0,
                        NOW(6), NOW(6), ?, ?)
                ON DUPLICATE KEY UPDATE job_id = job_id
                """, UUID.randomUUID().toString(), revisionId, observed.sourceCode(),
                observed.rawRecordId(), Date.valueOf(observed.fetchedDate()),
                observed.normalizerVersion(), observed.bodyHash(), revisionId,
                capturedLifecycleVersion);
    }

    private record DocPointer(long latestObserved, long lifecycleVersion) {}

    private record ExistingRevision(String docRevisionId, String bodyHash) {}

    private record LocalRedelivery(String docRevisionId, long revisionOrder) {}
}
