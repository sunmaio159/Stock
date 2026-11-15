package com.aquila.sentiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.ApiException;
import com.aquila.governance.GovernanceFixture;
import com.aquila.governance.RevisionService;
import com.aquila.masterdata.MasterDataFixture;
import com.aquila.sentiment.SentimentModel.AggregationPolicy;
import com.aquila.sentiment.SentimentModel.AggregationSample;
import com.aquila.sentiment.SentimentModel.AnalysisVersion;
import com.aquila.sentiment.SentimentModel.Eligibility;
import com.aquila.sentiment.SentimentModel.SentimentResult;
import com.aquila.sentiment.SentimentModel.WindowResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Persistence integration on real MySQL 8.4 for the parameter-independent store: a result
 * written once per (revision, engine level, algorithm, dictionary) tuple, an identical
 * replay that is a no-op, a conflicting replay that is refused because results are
 * immutable, and version-keyed windows that a rebuild replaces in place while a different
 * analysis version stays a separate row (never spliced).
 *
 * <p>No PO-owned value appears here: score, confidence, thresholds and eligibility all come
 * from the explicit test-local snapshots in {@link SentimentFixture}.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SentimentPersistenceIntegrationTest {

    private static final String ENGINE_LEVEL = "DOCUMENT";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RevisionService revisions;
    @Autowired private SentimentStore store;

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
        SentimentFixture.clear(jdbc);
    }

    @Test
    void unknownResultRoundTripsAsNullWithItsReason() {
        String revisionId = SentimentFixture.newRevision(jdbc, revisions);
        SentimentResult result = engine.evaluate(
                SentimentFixture.input(revisionId, "no dictionary term here", GovernanceFixture.SECURITY_A),
                SentimentFixture.dictionary(),
                SentimentFixture.algorithm());

        assertThat(store.persistResult(ENGINE_LEVEL, result)).isTrue();

        Map<String, Object> row = onlyResult();
        assertThat(row.get("score")).isNull();
        assertThat(row.get("confidence")).isNull();
        assertThat(row.get("unknown_reason")).isEqualTo("NO_HIT");
        assertThat(row.get("eligibility")).isEqualTo("ELIGIBLE");
        assertThat((String) row.get("hit_evidence")).contains("\"hits\"");
    }

    @Test
    void excludedAmbiguousResultKeepsItsOwnEvidenceAndReason() {
        String revisionId = SentimentFixture.newRevision(
                jdbc, revisions, GovernanceFixture.SECURITY_A, GovernanceFixture.SECURITY_B);
        SentimentResult result = engine.evaluate(
                SentimentFixture.input(revisionId, "gain decline",
                        GovernanceFixture.SECURITY_A, GovernanceFixture.SECURITY_B),
                SentimentFixture.dictionary(),
                SentimentFixture.algorithm());

        assertThat(result.eligibility()).isEqualTo(Eligibility.AMBIGUOUS_MULTI_SECURITY);
        assertThat(store.persistResult(ENGINE_LEVEL, result)).isTrue();

        Map<String, Object> row = onlyResult();
        // Excluded from directional aggregation, but the document-level evidence stays.
        assertThat(row.get("score")).isNotNull();
        assertThat(row.get("eligibility")).isEqualTo("AMBIGUOUS_MULTI_SECURITY");
        assertThat((String) row.get("exclusion_reason")).isNotBlank();
        assertThat((Boolean) row.get("is_opposite_stance")).isTrue();
    }

    @Test
    void replayingTheIdenticalResultIsANoOp() {
        String revisionId = SentimentFixture.newRevision(jdbc, revisions);
        SentimentResult result = evaluate(revisionId, "gain");

        assertThat(store.persistResult(ENGINE_LEVEL, result)).isTrue();
        assertThat(store.persistResult(ENGINE_LEVEL, result)).isFalse();
        assertThat(resultCount()).isEqualTo(1);
    }

    @Test
    void conflictingReplayForTheSameVersionTupleIsRefused() {
        String revisionId = SentimentFixture.newRevision(jdbc, revisions);
        SentimentResult stored = evaluate(revisionId, "gain");
        store.persistResult(ENGINE_LEVEL, stored);

        SentimentResult conflicting = new SentimentResult(
                stored.docRevisionId(), stored.algoVersion(), stored.dictVersion(),
                new BigDecimal("99.00"), stored.confidence(), null, stored.eligibility(),
                stored.exclusionReason(), stored.hitWords(), stored.oppositeStance());

        assertThatThrownBy(() -> store.persistResult(ENGINE_LEVEL, conflicting))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("immutable");
        assertThat(resultCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT score FROM m5_sentiment_result WHERE doc_revision_id = ?",
                BigDecimal.class, revisionId))
                .isEqualByComparingTo(stored.score());
    }

    @Test
    void resultForAnUnknownRevisionIsRejectedByTheForeignKey() {
        SentimentResult orphan = new SentimentResult(
                "revision-that-does-not-exist", SentimentFixture.ALGO_VERSION,
                SentimentFixture.DICT_VERSION, new BigDecimal("10.00"), new BigDecimal("0.5000"),
                null, Eligibility.ELIGIBLE, null, List.of("gain"), false);

        assertThatThrownBy(() -> store.persistResult(ENGINE_LEVEL, orphan))
                .isInstanceOf(DataAccessException.class);
        assertThat(resultCount()).isZero();
    }

    @Test
    void rebuildingTheSameAnalysisVersionReplacesTheWindowCounters() {
        String first = SentimentFixture.newRevision(jdbc, revisions);
        String second = SentimentFixture.newRevision(jdbc, revisions);

        store.persistWindow(aggregation.rebuild(
                SentimentFixture.windowKey("15m"),
                version("av-1", sample(first, 1, "20"))));
        store.persistWindow(aggregation.rebuild(
                SentimentFixture.windowKey("15m"),
                version("av-1", sample(first, 1, "20"), sample(second, 1, "40"))));

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
                SentimentFixture.windowKey("15m"), version("av-1", sample(revisionId, 1, "20"))));
        store.persistWindow(aggregation.rebuild(
                SentimentFixture.windowKey("15m"), version("av-2", sample(revisionId, 3, "-40"))));

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
        AggregationSample unknown = new AggregationSample(
                revisionId, List.of(GovernanceFixture.SECURITY_A), null, null, 1,
                Eligibility.ELIGIBLE, null, false);

        store.persistWindow(aggregation.rebuild(
                SentimentFixture.windowKey("1h"), version("av-unknown", unknown)));

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

    private static AggregationSample sample(String revisionId, int mentions, String score) {
        return new AggregationSample(
                revisionId,
                List.of(GovernanceFixture.SECURITY_A),
                new BigDecimal(score),
                new BigDecimal("0.8000"),
                mentions,
                Eligibility.ELIGIBLE,
                null,
                false);
    }

    private static AnalysisVersion version(String versionId, AggregationSample... samples) {
        return new AnalysisVersion(
                versionId,
                SentimentFixture.POLICY_VERSION,
                new AggregationPolicy(SentimentFixture.POLICY_VERSION, false, true),
                List.of(samples));
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
