package com.aquila.sentiment;

import com.aquila.crawl.JobOutcome;
import com.aquila.crawl.ProcessingJobWorker;
import com.aquila.governance.DocumentRevision;
import com.aquila.governance.GovernanceFixture;
import com.aquila.governance.RevisionService;
import com.aquila.sentiment.SentimentModel.AggregationPolicy;
import com.aquila.sentiment.SentimentModel.AggregationSample;
import com.aquila.sentiment.SentimentModel.AlgorithmSnapshot;
import com.aquila.sentiment.SentimentModel.AnalysisVersion;
import com.aquila.sentiment.SentimentModel.ConfidenceStrategy;
import com.aquila.sentiment.SentimentModel.DecayPolicy;
import com.aquila.sentiment.SentimentModel.DictionarySnapshot;
import com.aquila.sentiment.SentimentModel.Eligibility;
import com.aquila.sentiment.SentimentModel.SampleWeighting;
import com.aquila.sentiment.SentimentModel.SentimentInput;
import com.aquila.sentiment.SentimentModel.WindowKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared MySQL fixtures for the Stage D sentiment integration tests.
 *
 * <p>The version rows, term values, weights and decay shapes below are test-local data,
 * never production defaults: unified plan section 9.1/9.2 leaves the dictionary,
 * label/score mapping, thresholds, eligibility rules, section weights and aggregation
 * weights/decay to the PO, so the mechanism layer only ever consumes them as explicit
 * versioned inputs.
 */
public final class SentimentFixture {

    public static final String DICT_VERSION = "dict-it";
    public static final String ALGO_VERSION = "algo-it";
    public static final String POLICY_VERSION = "policy-it";
    public static final String WINDOW_END = "2026-11-15 11:15:00";
    public static final Instant PUBLISHED_AT = Instant.parse("2026-11-15T03:00:00Z");

    private SentimentFixture() {}

    /** Child-first reset: m5 rows first (they reference V4 revisions), then Stage C rows. */
    public static void reset(JdbcTemplate jdbc) {
        clearSentiment(jdbc);
        GovernanceFixture.reset(jdbc);
        seedVersions(jdbc);
    }

    public static void clear(JdbcTemplate jdbc) {
        clearSentiment(jdbc);
        GovernanceFixture.clear(jdbc);
    }

    private static void clearSentiment(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM m5_sentiment_result");
        jdbc.update("DELETE FROM m5_sentiment_window");
        jdbc.update("DELETE FROM m5_dirty_window");
        jdbc.update("DELETE FROM m5_dict_entry");
        jdbc.update("DELETE FROM m5_dict_version");
        jdbc.update("DELETE FROM m5_algorithm_version");
        jdbc.update("DELETE FROM m5_aggregation_policy_version");
    }

    /** One EXPERIMENTAL row per version: uncalibrated is the only honest initial state. */
    public static void seedVersions(JdbcTemplate jdbc) {
        insertDictVersion(jdbc, DICT_VERSION, "hash-" + DICT_VERSION);
        jdbc.update("""
                INSERT INTO m5_algorithm_version
                    (algo_version, ruleset_hash, description, published_at, created_at)
                VALUES (?, ?, 'integration fixture', NOW(6), NOW(6))
                """, ALGO_VERSION, "hash-" + ALGO_VERSION);
        insertPolicyVersion(jdbc, POLICY_VERSION);
    }

    public static void insertDictVersion(JdbcTemplate jdbc, String dictVersion, String contentHash) {
        jdbc.update("""
                INSERT INTO m5_dict_version
                    (dict_version, content_hash, entry_count, calibration_state,
                     published_at, created_at)
                VALUES (?, ?, 0, 'EXPERIMENTAL', NOW(6), NOW(6))
                """, dictVersion, contentHash);
    }

    public static void insertPolicyVersion(JdbcTemplate jdbc, String policyVersion) {
        jdbc.update("""
                INSERT INTO m5_aggregation_policy_version
                    (policy_version, weights_json, window_types, calibration_state,
                     published_at, created_at)
                VALUES (?, NULL, '15m,1h,24h', 'EXPERIMENTAL', NOW(6), NOW(6))
                """, policyVersion);
    }

    public static String newRevision(JdbcTemplate jdbc, RevisionService revisions) {
        return newRevision(jdbc, revisions, GovernanceFixture.SECURITY_A);
    }

    public static String newRevision(
            JdbcTemplate jdbc, RevisionService revisions, String... securityCodes) {
        String rawId = GovernanceFixture.insertRawRecord(
                jdbc, "outer-" + UUID.randomUUID(), "hash-" + UUID.randomUUID());
        DocumentRevision revision = revisions.receive(GovernanceFixture.observed(
                "doc-" + UUID.randomUUID(), 1L, "body-" + UUID.randomUUID(), rawId, securityCodes));
        return revision.docRevisionId();
    }

    /**
     * A revision fully processed by the Stage C governance worker — the only state a
     * sentiment result may be written against (as-built contract of
     * SentimentAnalysisService).
     */
    public static String processedRevision(
            JdbcTemplate jdbc, RevisionService revisions,
            ProcessingJobWorker worker, String... securityCodes) {
        String revisionId = newRevision(jdbc, revisions, securityCodes);
        String jobId = jdbc.queryForObject("""
                SELECT job_id FROM m3_processing_job WHERE doc_revision_id = ?
                """, String.class, revisionId);
        JobOutcome outcome = worker.process(worker.claim(jobId));
        if (outcome == null || !"COMPLETE".equals(outcome.state())) {
            throw new IllegalStateException("Governance processing failed: " + outcome);
        }
        return revisionId;
    }

    /** The lifecycle version the caller must capture before analysing a revision. */
    public static long lifecycleVersion(JdbcTemplate jdbc, String docRevisionId) {
        return jdbc.queryForObject("""
                SELECT d.lifecycle_version FROM m4_normalized_doc d
                JOIN m4_doc_revision r ON r.doc_id = d.doc_id
                WHERE r.doc_revision_id = ?
                """, Long.class, docRevisionId);
    }

    public static DictionarySnapshot dictionary() {
        return dictionary(DICT_VERSION);
    }

    public static DictionarySnapshot dictionary(String version) {
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("gain", new BigDecimal("40"));
        scores.put("decline", new BigDecimal("-40"));
        return new DictionarySnapshot(
                version, scores, Set.of("not"), Map.of("sharply", new BigDecimal("1.5")), false);
    }

    /** Explicit, uncalibrated 1:1 section weights — a test-local choice, not a default. */
    public static AlgorithmSnapshot algorithm() {
        return new AlgorithmSnapshot(
                ALGO_VERSION, new BigDecimal("0.10"), 2, 20, true,
                BigDecimal.ONE, BigDecimal.ONE, ConfidenceStrategy.HITS_OVER_FULL_HITS);
    }

    public static SentimentInput input(String revisionId, String text, String... securityCodes) {
        return new SentimentInput(revisionId, "", text, List.of(securityCodes));
    }

    public static WindowKey windowKey(String windowType) {
        return new WindowKey(
                GovernanceFixture.SECURITY_A, windowType, Instant.parse("2026-11-15T03:15:00Z"));
    }

    /** Explicit, uncalibrated policy: uniform weights, no decay (explicit NONE). */
    public static AggregationPolicy policy(boolean calibrated) {
        return new AggregationPolicy(
                POLICY_VERSION, calibrated, SampleWeighting.UNIFORM,
                new DecayPolicy(DecayPolicy.DecayType.NONE, null));
    }

    public static AnalysisVersion version(String versionId, AggregationSample... samples) {
        return new AnalysisVersion(versionId, POLICY_VERSION, policy(false), List.of(samples));
    }

    /** A single-security eligible sample published at the fixture instant. */
    public static AggregationSample sample(String revisionId, String score) {
        return sample(revisionId, 1, score);
    }

    public static AggregationSample sample(String revisionId, int mentions, String score) {
        return new AggregationSample(
                revisionId,
                List.of(GovernanceFixture.SECURITY_A),
                score == null ? null : new BigDecimal(score),
                new BigDecimal("0.8000"),
                mentions,
                Eligibility.ELIGIBLE,
                null,
                false,
                PUBLISHED_AT,
                revisionId);
    }
}
