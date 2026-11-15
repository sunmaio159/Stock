package com.aquila.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.masterdata.MasterDataFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * FT-02 falsifying tests: a late, lower-order revision must never move the observed or
 * processed pointer backwards. Both the source-supplied sequence and the local
 * max+1 fallback are exercised, and pointer writes are attempted out of order.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RevisionOrderingIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RevisionService revisions;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MasterDataFixture.register(registry);
    }

    @BeforeEach
    void reset() {
        GovernanceFixture.reset(jdbc);
    }

    @AfterEach
    void cleanup() {
        GovernanceFixture.clear(jdbc);
    }

    @Test
    void lateLowerSourceOrderCannotMoveObservedPointerBackwards() {
        String rawR2 = GovernanceFixture.insertRawRecord(jdbc, "POST-1", "hash-r2");
        String rawR1 = GovernanceFixture.insertRawRecord(jdbc, "POST-1", "hash-r1");

        DocumentRevision r2 = revisions.receive(GovernanceFixture.observed(
                "POST-1", 2L, "hash-r2", rawR2, GovernanceFixture.SECURITY_A));
        DocumentRevision r1 = revisions.receive(GovernanceFixture.observed(
                "POST-1", 1L, "hash-r1", rawR1, GovernanceFixture.SECURITY_B));

        assertThat(r2.revisionOrder()).isEqualTo(2);
        assertThat(r1.revisionOrder()).isEqualTo(1);
        assertThat(observedOrder(r2.docId())).isEqualTo(2);
        assertThat(currentRevision(r2.docId())).isEqualTo(r2.docRevisionId());
        assertThat(count("SELECT COUNT(*) FROM m4_doc_revision")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM m4_entity_mention")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM m4_entity_mention WHERE is_dirty = TRUE")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM m4_recompute_marker")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM m3_processing_job WHERE doc_revision_id IS NOT NULL"))
                .isEqualTo(2);
    }

    @Test
    void localOrderFallbackAssignsMonotonicOrdersAndRejectsLateLowOrder() {
        String rawFirst = GovernanceFixture.insertRawRecord(jdbc, "POST-2", "hash-1");
        String rawSecond = GovernanceFixture.insertRawRecord(jdbc, "POST-2", "hash-2");

        DocumentRevision first = revisions.receive(GovernanceFixture.observed(
                "POST-2", null, "hash-1", rawFirst, GovernanceFixture.SECURITY_A));
        DocumentRevision second = revisions.receive(GovernanceFixture.observed(
                "POST-2", null, "hash-2", rawSecond,
                GovernanceFixture.SECURITY_A, GovernanceFixture.SECURITY_B));

        assertThat(first.revisionOrder()).isEqualTo(1);
        assertThat(second.revisionOrder()).isEqualTo(2);
        assertThat(observedOrder(first.docId())).isEqualTo(2);
        assertThat(currentRevision(first.docId())).isEqualTo(second.docRevisionId());

        // A lower order completing late must be refused by both monotone pointers.
        assertThat(revisions.advanceObservedPointer(first.docId(), 1)).isFalse();
        assertThat(revisions.advanceProcessedPointer(first.docId(), 2)).isTrue();
        assertThat(revisions.advanceProcessedPointer(first.docId(), 1)).isFalse();
        assertThat(observedOrder(first.docId())).isEqualTo(2);
        assertThat(processedOrder(first.docId())).isEqualTo(2);
    }

    @Test
    void redeliveryOfTheSameSourceOrderIsIdempotent() {
        String raw = GovernanceFixture.insertRawRecord(jdbc, "POST-3", "hash-same");
        ObservedDocument document = GovernanceFixture.observed(
                "POST-3", 5L, "hash-same", raw, GovernanceFixture.SECURITY_A);

        DocumentRevision first = revisions.receive(document);
        DocumentRevision again = revisions.receive(document);

        assertThat(again.docRevisionId()).isEqualTo(first.docRevisionId());
        assertThat(count("SELECT COUNT(*) FROM m4_doc_revision")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM m4_entity_mention")).isEqualTo(1);
        assertThat(observedOrder(first.docId())).isEqualTo(5);
    }

    private long observedOrder(String docId) {
        return jdbc.queryForObject("""
                SELECT latest_observed_revision_order FROM m4_normalized_doc WHERE doc_id = ?
                """, Long.class, docId);
    }

    private long processedOrder(String docId) {
        return jdbc.queryForObject("""
                SELECT latest_processed_revision_order FROM m4_normalized_doc WHERE doc_id = ?
                """, Long.class, docId);
    }

    private String currentRevision(String docId) {
        return jdbc.queryForObject("""
                SELECT current_doc_revision_id FROM m4_normalized_doc WHERE doc_id = ?
                """, String.class, docId);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
