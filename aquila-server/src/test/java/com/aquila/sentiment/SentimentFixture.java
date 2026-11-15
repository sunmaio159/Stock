package com.aquila.sentiment;

import com.aquila.governance.DocumentRevision;
import com.aquila.governance.GovernanceFixture;
import com.aquila.governance.RevisionService;
import com.aquila.sentiment.SentimentModel.AlgorithmSnapshot;
import com.aquila.sentiment.SentimentModel.DictionarySnapshot;
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
 * <p>The version rows and term values below are test-local data, never production
 * defaults: unified plan section 9.1/9.2 leaves the dictionary, label/score mapping,
 * thresholds, eligibility rules and aggregation weights to the PO, so the mechanism layer
 * only ever consumes them as explicit versioned inputs.
 */
public final class SentimentFixture {

    public static final String DICT_VERSION = "dict-it";
    public static final String ALGO_VERSION = "algo-it";
    public static final String POLICY_VERSION = "policy-it";
    public static final String WINDOW_END = "2026-11-15 11:15:00";

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

    public static AlgorithmSnapshot algorithm() {
        return new AlgorithmSnapshot(ALGO_VERSION, new BigDecimal("0.10"), 2, 20, true);
    }

    public static SentimentInput input(String revisionId, String text, String... securityCodes) {
        return new SentimentInput(revisionId, "", text, List.of(securityCodes));
    }

    public static WindowKey windowKey(String windowType) {
        return new WindowKey(
                GovernanceFixture.SECURITY_A, windowType, Instant.parse("2026-11-15T03:15:00Z"));
    }
}
