package com.aquila.sentiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.governance.GovernanceFixture;
import com.aquila.governance.RevisionService;
import com.aquila.masterdata.MasterDataFixture;
import java.math.BigDecimal;
import java.util.UUID;
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
 * V5 constraints on real MySQL 8.4 (no H2, no mocks). These assert that the database
 * itself refuses the states unified plan section 9.1/9.2 forbids: unknown scored as a
 * half-zero, out-of-range score/confidence, an ambiguous result without a persisted
 * reason, a neutral-zero window score, duplicate version keys and duplicate
 * dirty-window generations.
 *
 * <p>Every case here is parameter-independent on purpose: it locks the shape of the
 * evidence (NULL vs zero, ranges, eligibility reasons, versioned keys), not any PO-owned
 * threshold, weight or dictionary content.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SentimentSchemaIntegrationTest {

    private static final String DEFAULT_ANALYSIS_VERSION = "av-it";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RevisionService revisions;

    private String revisionId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MasterDataFixture.register(registry);
    }

    @BeforeEach
    void reset() {
        SentimentFixture.reset(jdbc);
        revisionId = SentimentFixture.newRevision(jdbc, revisions);
    }

    @AfterEach
    void cleanup() {
        SentimentFixture.clear(jdbc);
    }

    @Test
    void unknownResultIsStoredAsNullWithAReason() {
        insertResult(null, null, "NO_HIT", "ELIGIBLE", null);

        assertThat(jdbc.queryForObject(
                "SELECT score FROM m5_sentiment_result WHERE doc_revision_id = ?",
                BigDecimal.class, revisionId))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT unknown_reason FROM m5_sentiment_result WHERE doc_revision_id = ?",
                String.class, revisionId))
                .isEqualTo("NO_HIT");
    }

    @Test
    void halfZeroUnknownResultIsRejected() {
        assertThatThrownBy(() -> insertResult(null, new BigDecimal("0.5000"), "NO_HIT", "ELIGIBLE", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertResult(BigDecimal.ZERO, new BigDecimal("0.5000"), "NO_HIT", "ELIGIBLE", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void scoreAndConfidenceBoundsAreEnforced() {
        assertThatThrownBy(() -> insertResult(new BigDecimal("101.00"), new BigDecimal("0.5000"), null, "ELIGIBLE", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertResult(new BigDecimal("-101.00"), new BigDecimal("0.5000"), null, "ELIGIBLE", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertResult(new BigDecimal("10.00"), new BigDecimal("1.5000"), null, "ELIGIBLE", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertResult(new BigDecimal("10.00"), new BigDecimal("-0.0001"), null, "ELIGIBLE", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void unknownShapeCannotCarryAHalfKnownOrReasonedScore() {
        // Score without confidence: neither known nor unknown.
        assertThatThrownBy(() -> insertResult(new BigDecimal("10.00"), null, null, "ELIGIBLE", null))
                .isInstanceOf(DataAccessException.class);
        // A known score cannot also carry an unknown reason.
        assertThatThrownBy(() -> insertResult(new BigDecimal("10.00"), new BigDecimal("0.5000"), "NO_HIT", "ELIGIBLE", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void ambiguousResultRequiresAPersistedExclusionReason() {
        assertThatThrownBy(() -> insertResult(new BigDecimal("10.00"), new BigDecimal("0.5000"), null,
                "AMBIGUOUS_MULTI_SECURITY", null))
                .isInstanceOf(DataAccessException.class);
        // The reverse direction: an eligible result must not carry an exclusion reason.
        assertThatThrownBy(() -> insertResult(new BigDecimal("10.00"), new BigDecimal("0.5000"), null,
                "ELIGIBLE", "excluded anyway"))
                .isInstanceOf(DataAccessException.class);

        insertResult(new BigDecimal("10.00"), new BigDecimal("0.5000"), null,
                "AMBIGUOUS_MULTI_SECURITY", "Opposite-stance content mentions multiple securities");
        assertThat(jdbc.queryForObject(
                "SELECT exclusion_reason FROM m5_sentiment_result WHERE doc_revision_id = ?",
                String.class, revisionId))
                .isNotBlank();
    }

    @Test
    void oneResultPerRevisionAndEngineLevel() {
        insertResult("DOCUMENT", revisionId, new BigDecimal("10.00"), new BigDecimal("0.5000"), null, "ELIGIBLE", null);

        assertThatThrownBy(() -> insertResult("DOCUMENT", revisionId, new BigDecimal("20.00"),
                new BigDecimal("0.5000"), null, "ELIGIBLE", null))
                .isInstanceOf(DataAccessException.class);

        // A different engine level is a different result, not a duplicate.
        insertResult("TITLE", revisionId, new BigDecimal("20.00"), new BigDecimal("0.5000"), null, "ELIGIBLE", null);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM m5_sentiment_result WHERE doc_revision_id = ?",
                Integer.class, revisionId))
                .isEqualTo(2);
    }

    @Test
    void resultRequiresAnExistingRevision() {
        assertThatThrownBy(() -> insertResult("DOCUMENT", "revision-that-does-not-exist",
                new BigDecimal("10.00"), new BigDecimal("0.5000"), null, "ELIGIBLE", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void dictionaryEntryScoreIsBoundedAndUniquePerSnapshot() {
        assertThatThrownBy(() -> insertEntry("STRONG_POSITIVE", "120.00"))
                .isInstanceOf(DataAccessException.class);
        // An entry cannot exist without its immutable snapshot header.
        assertThatThrownBy(() -> insertEntry("dict-missing", "STRONG_POSITIVE", "50.00"))
                .isInstanceOf(DataAccessException.class);

        insertEntry("STRONG_POSITIVE", "87.50");
        assertThatThrownBy(() -> insertEntry("STRONG_POSITIVE", "10.00"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void windowKeepsUnknownScoreNullAndSeparatesCounters() {
        insertWindow(null, 0, 3, 1);

        assertThat(jdbc.queryForObject(
                "SELECT score FROM m5_sentiment_window WHERE security_code = ?",
                BigDecimal.class, GovernanceFixture.SECURITY_A))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT mention_count - eligible_sample_count - excluded_ambiguous_count "
                        + "FROM m5_sentiment_window WHERE security_code = ?",
                Integer.class, GovernanceFixture.SECURITY_A))
                .isEqualTo(2);
        // Clear first: otherwise the unique window key, not the score CHECK, would reject
        // the row and this assertion would not prove the bound at all.
        jdbc.update("DELETE FROM m5_sentiment_window");
        assertThatThrownBy(() -> insertWindow(new BigDecimal("120.00"), 0, 3, 1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertWindow(null, 0, -1, 1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertWindow(null, -1, 0, 0))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void windowKeyIsScopedByAnalysisVersion() {
        insertWindow(DEFAULT_ANALYSIS_VERSION, SentimentFixture.POLICY_VERSION,
                GovernanceFixture.SECURITY_A, new BigDecimal("10.00"), 1, 1, 0);

        assertThatThrownBy(() -> insertWindow(DEFAULT_ANALYSIS_VERSION, SentimentFixture.POLICY_VERSION,
                GovernanceFixture.SECURITY_A, new BigDecimal("10.00"), 1, 1, 0))
                .isInstanceOf(DataAccessException.class);

        // A new analysis version gets its own row; history is never spliced into the old one.
        insertWindow("av-next", SentimentFixture.POLICY_VERSION,
                GovernanceFixture.SECURITY_A, new BigDecimal("20.00"), 1, 1, 0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM m5_sentiment_window WHERE security_code = ?",
                Integer.class, GovernanceFixture.SECURITY_A))
                .isEqualTo(2);
    }

    @Test
    void windowRequiresAKnownSecurityAndPolicyVersion() {
        assertThatThrownBy(() -> insertWindow(DEFAULT_ANALYSIS_VERSION, SentimentFixture.POLICY_VERSION,
                "SH:999999", new BigDecimal("10.00"), 1, 1, 0))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertWindow(DEFAULT_ANALYSIS_VERSION, "policy-that-does-not-exist",
                GovernanceFixture.SECURITY_A, new BigDecimal("10.00"), 1, 1, 0))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void dirtyWindowGenerationCannotBeDuplicated() {
        insertDirtyWindow(7L);
        assertThatThrownBy(() -> insertDirtyWindow(7L))
                .isInstanceOf(DataAccessException.class);
        // A later run is a new generation, not a duplicate.
        insertDirtyWindow(8L);
        assertThat(jdbc.queryForObject(
                "SELECT MAX(generation) FROM m5_dirty_window", Long.class))
                .isEqualTo(8L);
    }

    private void insertResult(
            BigDecimal score, BigDecimal confidence, String unknownReason,
            String eligibility, String exclusionReason) {
        insertResult("DOCUMENT", revisionId, score, confidence, unknownReason,
                eligibility, exclusionReason);
    }

    private void insertResult(
            String engineLevel, String targetRevisionId, BigDecimal score, BigDecimal confidence,
            String unknownReason, String eligibility, String exclusionReason) {
        jdbc.update("""
                INSERT INTO m5_sentiment_result
                    (result_id, doc_revision_id, engine_level, algo_version, dict_version,
                     score, confidence, unknown_reason, hit_evidence, eligibility,
                     exclusion_reason, is_opposite_stance, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, FALSE, NOW(6))
                """, UUID.randomUUID().toString(), targetRevisionId, engineLevel,
                SentimentFixture.ALGO_VERSION, SentimentFixture.DICT_VERSION,
                score, confidence, unknownReason, eligibility, exclusionReason);
    }

    private void insertEntry(String word, String score) {
        insertEntry(SentimentFixture.DICT_VERSION, word, score);
    }

    private void insertEntry(String dictVersion, String word, String score) {
        jdbc.update("""
                INSERT INTO m5_dict_entry
                    (dict_version, dict_type, word, score, created_at)
                VALUES (?, 'WORD', ?, ?, NOW(6))
                """, dictVersion, word, new BigDecimal(score));
    }

    private void insertWindow(BigDecimal score, int eligible, int mentions, int excluded) {
        insertWindow(DEFAULT_ANALYSIS_VERSION, SentimentFixture.POLICY_VERSION,
                GovernanceFixture.SECURITY_A, score, eligible, mentions, excluded);
    }

    private void insertWindow(
            String analysisVersion, String policyVersion, String securityCode,
            BigDecimal score, int eligible, int mentions, int excluded) {
        jdbc.update("""
                INSERT INTO m5_sentiment_window
                    (window_id, security_code, window_type, window_end, analysis_version,
                     aggregation_policy_version, score, eligible_sample_count, mention_count,
                     excluded_ambiguous_count, evidence_state, created_at)
                VALUES (?, ?, '15m', ?, ?, ?, ?, ?, ?, ?, 'EXPERIMENTAL', NOW(6))
                """, UUID.randomUUID().toString(), securityCode,
                SentimentFixture.WINDOW_END, analysisVersion, policyVersion,
                score, eligible, mentions, excluded);
    }

    private void insertDirtyWindow(long generation) {
        jdbc.update("""
                INSERT INTO m5_dirty_window
                    (dirty_id, security_code, window_type, window_end, generation,
                     state, consumed_at, created_at)
                VALUES (?, ?, '15m', ?, ?, 'PENDING', NULL, NOW(6))
                """, UUID.randomUUID().toString(), GovernanceFixture.SECURITY_A,
                SentimentFixture.WINDOW_END, generation);
    }
}
