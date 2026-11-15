package com.aquila.sentiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.ApiException;
import com.aquila.crawl.ProcessingJobWorker;
import com.aquila.governance.GovernanceFixture;
import com.aquila.governance.RevisionService;
import com.aquila.masterdata.MasterDataFixture;
import com.aquila.sentiment.SentimentModel.Eligibility;
import com.aquila.sentiment.SentimentModel.SentimentResult;
import com.aquila.sentiment.SentimentModel.WindowResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistence integration on real MySQL 8.4 for the parameter-independent store: a result
 * written once per (revision, engine level, algorithm, dictionary) tuple behind the full
 * Stage C lifecycle barrier, an identical replay that is a no-op, a conflicting replay
 * that is refused because results are immutable, numeric payloads beyond the column
 * precision that are normalised instead of manufacturing a conflict, and version-keyed
 * windows that a rebuild replaces in place while a different analysis version stays a
 * separate row (never spliced).
 *
 * <p>No PO-owned value appears here: score, confidence, thresholds and eligibility all
 * come from the explicit test-local snapshots in {@link SentimentFixture}.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SentimentPersistenceIntegrationTest {

    private static final String ENGINE_LEVEL = "DOCUMENT";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RevisionService revisions;
    @Autowired private ProcessingJobWorker worker;
    @Autowired private SentimentStore store;
    @Autowired private SentimentAnalysisService analysis;
    @Autowired private TransactionTemplate transactions;

    private final SentimentEngine engine = new SentimentEngine();
    private final AggregationService aggregation = new AggregationService();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MasterDataFixture.register(registry);
    }

    @BeforeEach
    void reset() {
        SentimentFixture.reset(jdbc);
    }

    @AfterEach
    void cleanup() {
        analysis.setBeforeCommitHook(null);
        SentimentFixture.clear(jdbc);
    }

    @Test
    void unknownResultRoundTripsAsNullWithItsReason() {
        String revisionId = SentimentFixture.processedRevision(jdbc, revisions, worker);
        SentimentResult result = engine.evaluate(
                SentimentFixture.input(revisionId, "no dictionary term here", GovernanceFixture.SECURITY_A),
                SentimentFixture.dictionary(),
                SentimentFixture.algorithm());

        assertThat(analysis.persistResult(
                ENGINE_LEVEL, result, SentimentFixture.lifecycleVersion(jdbc, revisionId))).isTrue();

        Map<String, Object> row = onlyResult();
        assertThat(row.get("score")).isNull();
        assertThat(row.get("confidence")).isNull();
        assertThat(row.get("unknown_reason")).isEqualTo("NO_HIT");
        assertThat(row.get("eligibility")).isEqualTo("ELIGIBLE");
        assertThat((String) row.get("hit_evidence")).contains("\"hits\"");
    }

    @Test
    void excludedAmbiguousResultKeepsItsOwnEvidenceAndReason() {
        String revisionId = SentimentFixture.processedRevision(
                jdbc, revisions, worker, GovernanceFixture.SECURITY_A, GovernanceFixture.SECURITY_B);
        SentimentResult result = engine.evaluate(
                SentimentFixture.input(revisionId, "gain decline",
                        GovernanceFixture.SECURITY_A, GovernanceFixture.SECURITY_B),
                SentimentFixture.dictionary(),
                SentimentFixture.algorithm());

        assertThat(result.eligibility()).isEqualTo(Eligibility.AMBIGUOUS_MULTI_SECURITY);
        assertThat(analysis.persistResult(
                ENGINE_LEVEL, result, SentimentFixture.lifecycleVersion(jdbc, revisionId))).isTrue();

        Map<String, Object> row = onlyResult();
        // Excluded from directional aggregation, but the document-level evidence stays.
        assertThat(row.get("score")).isNotNull();
        assertThat(row.get("eligibility")).isEqualTo("AMBIGUOUS_MULTI_SECURITY");
        assertThat((String) row.get("exclusion_reason")).isNotBlank();
        assertThat((Boolean) row.get("is_opposite_stance")).isTrue();
    }

    @Test
    void replayingTheIdenticalResultIsANoOp() {
        String revisionId = SentimentFixture.processedRevision(jdbc, revisions, worker);
        SentimentResult result = evaluate(revisionId, "gain");
        long version = SentimentFixture.lifecycleVersion(jdbc, revisionId);

        assertThat(analysis.persistResult(ENGINE_LEVEL, result, version)).isTrue();
        assertThat(analysis.persistResult(ENGINE_LEVEL, result, version)).isFalse();
        assertThat(resultCount()).isEqualTo(1);
    }

    @Test
    void conflictingReplayForTheSameVersionTupleIsRefused() {
        String revisionId = SentimentFixture.processedRevision(jdbc, revisions, worker);
        SentimentResult stored = evaluate(revisionId, "gain");
        long version = SentimentFixture.lifecycleVersion(jdbc, revisionId);
        assertThat(analysis.persistResult(ENGINE_LEVEL, stored, version)).isTrue();

        SentimentResult conflicting = new SentimentResult(
                stored.docRevisionId(), stored.algoVersion(), stored.dictVersion(),
                new BigDecimal("99.00"), stored.confidence(), null, stored.eligibility(),
                stored.exclusionReason(), stored.hitWords(), stored.oppositeStance());

        assertThatThrownBy(() -> analysis.persistResult(ENGINE_LEVEL, conflicting, version))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("immutable");
        assertThat(resultCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT score FROM m5_sentiment_result WHERE doc_revision_id = ?",
                BigDecimal.class, revisionId))
                .isEqualByComparingTo(stored.score());
    }

    /**
     * Review I-3: DECIMAL(6,2)/DECIMAL(5,4) columns silently round, so a payload beyond
     * the column precision used to make an identical replay look like a conflict (the DB
     * stored 10.00 while Java compared against 10.004). The store normalises before the
     * write and before the comparison, so the replay is a no-op.
     */
    @Test
    void overPrecisionPayloadIsNormalisedNotAConflict() {
        String revisionId = SentimentFixture.processedRevision(jdbc, revisions, worker);
        SentimentResult result = new SentimentResult(
                revisionId, SentimentFixture.ALGO_VERSION, SentimentFixture.DICT_VERSION,
                new BigDecimal("10.004"), new BigDecimal("0.50001"), null,
                Eligibility.ELIGIBLE, null, List.of("gain"), false);
        long version = SentimentFixture.lifecycleVersion(jdbc, revisionId);

        assertThat(analysis.persistResult(ENGINE_LEVEL, result, version)).isTrue();
        assertThat(analysis.persistResult(ENGINE_LEVEL, result, version)).isFalse();

        assertThat(resultCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT score FROM m5_sentiment_result WHERE doc_revision_id = ?",
                BigDecimal.class, revisionId))
                .isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject(
                "SELECT confidence FROM m5_sentiment_result WHERE doc_revision_id = ?",
                BigDecimal.class, revisionId))
                .isEqualByComparingTo("0.5000");
    }

    /**
     * Review I-4: a replay running inside a REPEATABLE READ transaction whose snapshot
     * predates the concurrent winner must still resolve the duplicate to a no-op. The
     * replay opens its snapshot with a plain read, waits for the winner to commit, then
     * replays; the store's duplicate-key readback is a current read (FOR UPDATE), so it
     * sees the committed row. A plain readback keeps the old ReadView, misses the row and
     * re-throws the duplicate — that is the red state.
     */
    @Test
    void aReplayInsideAnOldSnapshotStillSeesTheCommittedWinner() throws Exception {
        String revisionId = SentimentFixture.processedRevision(jdbc, revisions, worker);
        SentimentResult result = evaluate(revisionId, "gain");
        long version = SentimentFixture.lifecycleVersion(jdbc, revisionId);

        CountDownLatch snapshotOpen = new CountDownLatch(1);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Boolean> replay = pool.submit(() -> transactions.execute(status -> {
                // Plain read: establishes this transaction's REPEATABLE READ snapshot.
                jdbc.queryForObject("SELECT COUNT(*) FROM m5_dict_version", Integer.class);
                snapshotOpen.countDown();
                try {
                    assertThat(winnerCommitted.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return analysis.persistResult(ENGINE_LEVEL, result, version);
            }));

            assertThat(snapshotOpen.await(30, TimeUnit.SECONDS)).isTrue();
            // The winner commits after the replay snapshot was established.
            assertThat(analysis.persistResult(ENGINE_LEVEL, result, version)).isTrue();
            winnerCommitted.countDown();

            assertThat(replay.get(30, TimeUnit.SECONDS))
                    .as("the replay resolves to a no-op instead of re-throwing the duplicate")
                    .isFalse();
        } finally {
            pool.shutdownNow();
        }
        assertThat(resultCount()).isEqualTo(1);
    }

    @Test
    void rebuildingTheSameAnalysisVersionReplacesTheWindowCounters() {
        String first = SentimentFixture.newRevision(jdbc, revisions);
        String second = SentimentFixture.newRevision(jdbc, revisions);

        store.persistWindow(aggregation.rebuild(
                SentimentFixture.windowKey("15m"),
                SentimentFixture.version("av-1", SentimentFixture.sample(first, "20"))));
        store.persistWindow(aggregation.rebuild(
                SentimentFixture.windowKey("15m"),
                SentimentFixture.version("av-1",
                        SentimentFixture.sample(first, "20"), SentimentFixture.sample(second, "40"))));

        assertThat(windowCount()).isEqualTo(1);
        Map<String, Object> row = onlyWindow();
        assertThat(row.get("analysis_version")).isEqualTo("av-1");
        assertThat((Integer) row.get("eligible_sample_count")).isEqualTo(2);
        assertThat((Integer) row.get("mention_count")).isEqualTo(2);
        assertThat((BigDecimal) row.get("score")).isEqualByComparingTo("30.00");
        // The uncalibrated policy cannot present its output as a complete fact.
        assertThat(row.get("evidence_state")).isEqualTo("EXPERIMENTAL");
    }

    @Test
    void anotherAnalysisVersionKeepsItsOwnWindowRow() {
        String revisionId = SentimentFixture.newRevision(jdbc, revisions);
        store.persistWindow(aggregation.rebuild(
                SentimentFixture.windowKey("15m"),
                SentimentFixture.version("av-1", SentimentFixture.sample(revisionId, "20"))));
        store.persistWindow(aggregation.rebuild(
                SentimentFixture.windowKey("15m"),
                SentimentFixture.version("av-2", SentimentFixture.sample(revisionId, 3, "-40"))));

        assertThat(windowCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT score FROM m5_sentiment_window
                WHERE analysis_version = 'av-1' AND security_code = ?
                """, BigDecimal.class, GovernanceFixture.SECURITY_A))
                .isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("""
                SELECT mention_count FROM m5_sentiment_window
                WHERE analysis_version = 'av-2' AND security_code = ?
                """, Integer.class, GovernanceFixture.SECURITY_A))
                .isEqualTo(3);
    }

    @Test
    void windowWithNoEligibleSampleKeepsItsScoreNull() {
        String revisionId = SentimentFixture.newRevision(jdbc, revisions);
        com.aquila.sentiment.SentimentModel.AggregationSample unknown =
                new com.aquila.sentiment.SentimentModel.AggregationSample(
                        revisionId, List.of(GovernanceFixture.SECURITY_A), null, null, 1,
                        Eligibility.ELIGIBLE, null, false,
                        SentimentFixture.PUBLISHED_AT, revisionId);

        store.persistWindow(aggregation.rebuild(
                SentimentFixture.windowKey("1h"), SentimentFixture.version("av-unknown", unknown)));

        Map<String, Object> row = onlyWindow();
        assertThat(row.get("score")).isNull();
        assertThat((Integer) row.get("eligible_sample_count")).isZero();
        assertThat((Integer) row.get("mention_count")).isEqualTo(1);
        assertThat(row.get("window_type")).isEqualTo("1h");
    }

    private SentimentResult evaluate(String revisionId, String text) {
        return engine.evaluate(
                SentimentFixture.input(revisionId, text, GovernanceFixture.SECURITY_A),
                SentimentFixture.dictionary(),
                SentimentFixture.algorithm());
    }

    private Map<String, Object> onlyResult() {
        return jdbc.queryForMap("SELECT * FROM m5_sentiment_result");
    }

    private Map<String, Object> onlyWindow() {
        return jdbc.queryForMap("SELECT * FROM m5_sentiment_window");
    }

    private int resultCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM m5_sentiment_result", Integer.class);
    }

    private int windowCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM m5_sentiment_window", Integer.class);
    }
}
