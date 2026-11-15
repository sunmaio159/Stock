package com.aquila.sentiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.governance.DocumentRevision;
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
 * reason, a neutral-zero window score and duplicate dirty-window generations.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SentimentSchemaIntegrationTest {

    private static final String DICT_VERSION = "dict-it";
    private static final String ALGO_VERSION = "algo-it";
    private static final String POLICY_VERSION = "policy-it";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RevisionService revisions;

    private String revisionId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MasterDataFixture.register(registry);
    }

    @BeforeEach
    void reset() {
        GovernanceFixture.reset(jdbc);
        jdbc.update("""
                INSERT INTO m5_dict_version
                    (dict_version, content_hash, entry_count, calibration_state,
                     published_at, created_at)
                VALUES (?, 'hash-it', 0, 'EXPERIMENTAL', NOW(6), NOW(6))
                """, DICT_VERSION);
        jdbc.update("""
                INSERT INTO m5_algorithm_version
                    (algo_version, ruleset_hash, description, published_at, created_at)
                VALUES (?, 'hash-algo-it', 'integration fixture', NOW(6), NOW(6))
                """, ALGO_VERSION);
        jdbc.update("""
                INSERT INTO m5_aggregation_policy_version
                    (policy_version, weights_json, window_types, calibration_state,
                     published_at, created_at)
                VALUES (?, NULL, '15m,1h,24h', 'EXPERIMENTAL', NOW(6), NOW(6))
                """, POLICY_VERSION);
        String rawId = GovernanceFixture.insertRawRecord(
                jdbc, "outer-" + UUID.randomUUID(), "hash-" + UUID.randomUUID());
        DocumentRevision revision = revisions.receive(GovernanceFixture.observed(
                "doc-" + UUID.randomUUID(), 1L, "body-hash", rawId, GovernanceFixture.SECURITY_A));
        revisionId = revision.docRevisionId();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM m5_sentiment_result");
        jdbc.update("DELETE FROM m5_sentiment_window");
        jdbc.update("DELETE FROM m5_dirty_window");
        jdbc.update("DELETE FROM m5_dict_entry");
        jdbc.update("DELETE FROM m5_dict_version");
        jdbc.update("DELETE FROM m5_algorithm_version");
        jdbc.update("DELETE FROM m5_aggregation_policy_version");
        GovernanceFixture.clear(jdbc);
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
        assertThatThrownBy(() -> insertResult(new BigDecimal("10.00"), new BigDecimal("1.5000"), null, "ELIGIBLE", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void ambiguousResultRequiresAPersistedExclusionReason() {
        assertThatThrownBy(() -> insertResult(new BigDecimal("10.00"), new BigDecimal("0.5000"), null,
                "AMBIGUOUS_MULTI_SECURITY", null))
                .isInstanceOf(DataAccessException.class);

        insertResult(new BigDecimal("10.00"), new BigDecimal("0.5000"), null,
                "AMBIGUOUS_MULTI_SECURITY", "Opposite-stance content mentions multiple securities");
        assertThat(jdbc.queryForObject(
                "SELECT exclusion_reason FROM m5_sentiment_result WHERE doc_revision_id = ?",
                String.class, revisionId))
                .isNotBlank();
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
    }

    @Test
    void dirtyWindowGenerationCannotBeDuplicated() {
        insertDirtyWindow(7L);
        assertThatThrownBy(() -> insertDirtyWindow(7L))
                .isInstanceOf(DataAccessException.class);
    }

    private void insertResult(
            BigDecimal score, BigDecimal confidence, String unknownReason,
            String eligibility, String exclusionReason) {
        jdbc.update("""
                INSERT INTO m5_sentiment_result
                    (result_id, doc_revision_id, engine_level, algo_version, dict_version,
                     score, confidence, unknown_reason, hit_evidence, eligibility,
                     exclusion_reason, is_opposite_stance, created_at)
                VALUES (?, ?, 'DOCUMENT', ?, ?, ?, ?, ?, NULL, ?, ?, FALSE, NOW(6))
                """, UUID.randomUUID().toString(), revisionId, ALGO_VERSION, DICT_VERSION,
                score, confidence, unknownReason, eligibility, exclusionReason);
    }

    private void insertWindow(BigDecimal score, int eligible, int mentions, int excluded) {
        jdbc.update("""
                INSERT INTO m5_sentiment_window
                    (window_id, security_code, window_type, window_end, analysis_version,
                     aggregation_policy_version, score, eligible_sample_count, mention_count,
                     excluded_ambiguous_count, evidence_state, created_at)
                VALUES (?, ?, '15m', '2026-11-15 11:15:00', 'av-it', ?, ?, ?, ?, ?,
                        'EXPERIMENTAL', NOW(6))
                """, UUID.randomUUID().toString(), GovernanceFixture.SECURITY_A,
                POLICY_VERSION, score, eligible, mentions, excluded);
    }

    private void insertDirtyWindow(long generation) {
        jdbc.update("""
                INSERT INTO m5_dirty_window
                    (dirty_id, security_code, window_type, window_end, generation,
                     state, consumed_at, created_at)
                VALUES (?, ?, '15m', '2026-11-15 11:15:00', ?, 'PENDING', NULL, NOW(6))
                """, UUID.randomUUID().toString(), GovernanceFixture.SECURITY_A, generation);
    }
}
