package com.aquila.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.crawl.JobLease;
import com.aquila.crawl.ProcessingJobWorker;
import com.aquila.masterdata.MasterDataFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    @Autowired private ProcessingJobWorker worker;

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

    /**
     * Review round-1 I-2: FT-02 must hold at the worker level, not only for direct pointer
     * calls. Processing R2's PARSE job first and R1's afterwards must leave the processed
     * pointer at 2; removing the monotone WHERE on doAdvanceProcessed makes this red.
     */
    @Test
    void processingJobsOutOfOrderCannotMoveProcessedPointerBackwards() {
        String rawR1 = GovernanceFixture.insertRawRecord(jdbc, "POST-4", "hash-r1");
        String rawR2 = GovernanceFixture.insertRawRecord(jdbc, "POST-4", "hash-r2");
        DocumentRevision r1 = revisions.receive(GovernanceFixture.observed(
                "POST-4", 1L, "hash-r1", rawR1, GovernanceFixture.SECURITY_A));
        DocumentRevision r2 = revisions.receive(GovernanceFixture.observed(
                "POST-4", 2L, "hash-r2", rawR2, GovernanceFixture.SECURITY_A));

        JobLease lease2 = worker.claim(jobFor(r2.docRevisionId()));
        assertThat(lease2).isNotNull();
        assertThat(worker.process(lease2).state()).isEqualTo("COMPLETE");
        assertThat(processedOrder(r1.docId())).isEqualTo(2);

        JobLease lease1 = worker.claim(jobFor(r1.docRevisionId()));
        assertThat(lease1).isNotNull();
        assertThat(worker.process(lease1).state()).isEqualTo("COMPLETE");
        assertThat(processedOrder(r1.docId())).isEqualTo(2);
        assertThat(currentRevision(r1.docId())).isEqualTo(r2.docRevisionId());
    }

    /** Review round-1 I-4: local-order redelivery must be a no-op, not a new revision. */
    @Test
    void localOrderRedeliveryIsIdempotent() {
        String raw = GovernanceFixture.insertRawRecord(jdbc, "POST-5", "hash-repeat");
        ObservedDocument document = GovernanceFixture.observed(
                "POST-5", null, "hash-repeat", raw, GovernanceFixture.SECURITY_A);

        DocumentRevision first = revisions.receive(document);
        DocumentRevision again = revisions.receive(document);

        assertThat(again.docRevisionId()).isEqualTo(first.docRevisionId());
        assertThat(again.revisionOrder()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM m4_doc_revision")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM m3_processing_job WHERE doc_revision_id IS NOT NULL"))
                .isEqualTo(1);
        assertThat(observedOrder(first.docId())).isEqualTo(1);
    }

    /**
     * Review round-2 Important-1: two concurrent redeliveries of the same local-order
     * observation must still produce ONE revision. Under REPEATABLE READ, a consistent-read
     * snapshot taken before the doc row lock hides the first transaction's revision and
     * silently allocates a second order. This test is intentionally run against real MySQL
     * with two threads (each gets its own connection/transaction).
     */
    @Test
    void concurrentLocalOrderRedeliveryCreatesOneRevision() throws Exception {
        String raw = GovernanceFixture.insertRawRecord(jdbc, "POST-6", "hash-concurrent");
        ObservedDocument document = GovernanceFixture.observed(
                "POST-6", null, "hash-concurrent", raw, GovernanceFixture.SECURITY_A);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<DocumentRevision>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return revisions.receive(document);
                }));
            }
            start.countDown();
            for (Future<DocumentRevision> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT COUNT(*) FROM m4_doc_revision")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM m3_processing_job WHERE doc_revision_id IS NOT NULL"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT latest_observed_revision_order FROM m4_normalized_doc
                """, Long.class)).isEqualTo(1);
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

    private String jobFor(String revisionId) {
        return jdbc.queryForObject("""
                SELECT job_id FROM m3_processing_job WHERE doc_revision_id = ?
                """, String.class, revisionId);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
