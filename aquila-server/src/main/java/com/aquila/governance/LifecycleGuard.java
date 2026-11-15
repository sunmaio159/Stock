package com.aquila.governance;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Commit barrier for derived writes (Stage C Task 2 / FT-03).
 *
 * <p>Every check uses a locking (current) read. Under MySQL's default REPEATABLE READ a
 * plain read started when the job was claimed would keep showing the pre-revocation
 * snapshot, so a revoke committed by another connection between "compute" and "commit"
 * would be invisible. {@code FOR UPDATE} bypasses the snapshot and observes the latest
 * committed lifecycle status, lifecycle version and source grants.
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class LifecycleGuard {

    private final JdbcTemplate jdbc;

    public LifecycleGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void assertCommittable(DocRef ref, long capturedLifecycleVersion) {
        List<LifecycleRow> documents = jdbc.query("""
                SELECT lifecycle_status, lifecycle_version
                FROM m4_normalized_doc
                WHERE doc_id = ? FOR UPDATE
                """, (resultSet, rowNumber) ->
                        new LifecycleRow(resultSet.getString(1), resultSet.getLong(2)),
                ref.docId());
        if (documents.isEmpty()) {
            throw new LifecycleBlockedException("REVOKED", "Document no longer exists");
        }
        LifecycleRow document = documents.get(0);
        if (!"ACTIVE".equals(document.status())) {
            throw new LifecycleBlockedException(
                    "REVOKED", "Document lifecycle is " + document.status());
        }
        if (document.version() != capturedLifecycleVersion) {
            throw new LifecycleBlockedException(
                    "EXPIRED", "Lifecycle version changed before commit");
        }

        List<SourceRow> sources = jdbc.query("""
                SELECT permission_status, perm_analyze, perm_store_normalized
                FROM m3_source_config
                WHERE source_code = ? FOR UPDATE
                """, (resultSet, rowNumber) -> new SourceRow(
                        resultSet.getString(1), resultSet.getBoolean(2), resultSet.getBoolean(3)),
                ref.sourceCode());
        if (sources.isEmpty()) {
            throw new LifecycleBlockedException("REVOKED", "Source configuration is missing");
        }
        SourceRow source = sources.get(0);
        if (!"ACTIVE".equals(source.status())
                || !source.analyzeGranted()
                || !source.storeNormalizedGranted()) {
            throw new LifecycleBlockedException(
                    "REVOKED", "Source permission revoked or capability withheld");
        }
    }

    /**
     * Detects an out-of-band change to a revision's content columns. The expression is
     * byte-for-byte the one RevisionService uses to write content_fingerprint; evaluating
     * it in SQL over the stored row avoids JDBC/DB timestamp-format drift. A mismatch is
     * a non-retryable REVOKED outcome, so a tampered revision can never produce derived
     * content. Current read (FOR UPDATE) keeps it in the same locking domain as the
     * lifecycle checks.
     */
    public void assertContentUnchanged(String docRevisionId) {
        List<Boolean> matches = jdbc.query("""
                SELECT content_fingerprint = SHA2(CONCAT_WS('|',
                           doc_id, revision_order, body_hash, raw_record_id, fetched_date,
                           published_at, observed_at, normalizer_version, parser_version), 256)
                FROM m4_doc_revision
                WHERE doc_revision_id = ? FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getBoolean(1), docRevisionId);
        if (matches.size() != 1 || !matches.get(0)) {
            throw new LifecycleBlockedException(
                    "REVOKED", "Revision content changed after insert");
        }
    }

    private record LifecycleRow(String status, long version) {}

    private record SourceRow(
            String status, boolean analyzeGranted, boolean storeNormalizedGranted) {}
}
