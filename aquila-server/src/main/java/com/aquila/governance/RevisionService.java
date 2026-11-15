package com.aquila.governance;

import com.aquila.common.ApiException;
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

    public RevisionService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public DocumentRevision receive(ObservedDocument observed) {
        return transactions.execute(status -> {
            String docId = upsertDocument(observed);

            List<DocPointer> pointers = jdbc.query("""
                    SELECT latest_observed_revision_order, lifecycle_version
                    FROM m4_normalized_doc
                    WHERE doc_id = ? FOR UPDATE
                    """, (resultSet, rowNumber) ->
                            new DocPointer(resultSet.getLong(1), resultSet.getLong(2)),
                    docId);
            DocPointer pointer = pointers.get(0);

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
        jdbc.update("""
                INSERT INTO m4_normalized_doc
                    (doc_id, source_code, outer_id, current_doc_revision_id,
                     latest_observed_revision_order, latest_processed_revision_order,
                     lifecycle_status, lifecycle_version, created_at, updated_at)
                VALUES (?, ?, ?, NULL, 0, 0, 'ACTIVE', 0, NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE doc_id = doc_id
                """, UUID.randomUUID().toString(), observed.sourceCode(), observed.outerId());
        return jdbc.queryForObject("""
                SELECT doc_id FROM m4_normalized_doc WHERE source_code = ? AND outer_id = ?
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
}
