package com.aquila.sentiment;

import com.aquila.common.ApiException;
import com.aquila.sentiment.SentimentModel.SentimentResult;
import com.aquila.sentiment.SentimentModel.WindowResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Writes Stage D sentiment evidence into V5/V6. Nothing here decides a PO-owned value: the
 * score, confidence, unknown reason, eligibility, exclusion reason and window counters all
 * arrive already computed from explicit versioned inputs (unified plan section 9.1/9.2), so
 * this class stays parameter-free and is safe to build before Step 1 is signed.
 *
 * <p>Two invariants are enforced here rather than by a trigger (the runtime account has no
 * SUPER, so MySQL refuses one — see the V4 notes in the evidence register):
 *
 * <ul>
 *   <li>A result is immutable per {@code (doc_revision_id, engine_level, algo_version,
 *       dict_version)}. An identical replay is a no-op; a different payload under the same
 *       tuple is refused, because deterministic versioned inputs cannot produce two answers.
 *   <li>A window row is keyed by {@code (security, window_type, window_end, analysis_version,
 *       aggregation_policy_version)}. Rebuilding the same analysis version replaces that
 *       row's counters in place; a different analysis version keeps its own row, so versioned
 *       history is never spliced. Evidence-state transitions belong to the Task 2
 *       publication barrier and are only carried through here.
 * </ul>
 *
 * <p>Stage D review 2026-11-15: {@link #persistResult} is package-private on purpose — the
 * only production entry point for a document result is {@link SentimentAnalysisService},
 * which runs the Stage C lifecycle/content barrier in the same transaction (review C-3).
 * Numeric payloads are normalised to the column precision before writing and before the
 * replay comparison, so a caller-supplied value beyond DECIMAL(6,2)/DECIMAL(5,4) cannot
 * make an identical replay look like a conflict (review I-3). The duplicate-key readback
 * is a locking (current) read, so a replay inside a caller transaction whose REPEATABLE
 * READ snapshot predates the concurrent winner still sees the committed row instead of
 * re-throwing the duplicate (review I-4).
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class SentimentStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SentimentStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Persists one document-level result. Returns {@code true} when the row was inserted and
     * {@code false} when the identical result was already stored (at-least-once replay).
     * Package-private: derived results must go through the guarded
     * {@link SentimentAnalysisService}.
     *
     * @throws ApiException conflict when the same version tuple already stores another value
     */
    boolean persistResult(String engineLevel, SentimentResult result) {
        requireText(engineLevel, "engineLevel");
        Objects.requireNonNull(result, "result");
        requireText(result.docRevisionId(), "docRevisionId");
        requireText(result.algoVersion(), "algoVersion");
        requireText(result.dictVersion(), "dictVersion");
        if (result.eligibility() == null) {
            throw new IllegalArgumentException("eligibility must be explicit");
        }

        JsonNode evidence = evidenceNode(result);
        try {
            jdbc.update("""
                    INSERT INTO m5_sentiment_result
                        (result_id, doc_revision_id, engine_level, algo_version, dict_version,
                         score, confidence, unknown_reason, hit_evidence, eligibility,
                         exclusion_reason, is_opposite_stance, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6))
                    """, UUID.randomUUID().toString(), result.docRevisionId(), engineLevel,
                    result.algoVersion(), result.dictVersion(), normalizeScore(result.score()),
                    normalizeConfidence(result.confidence()), result.unknownReason(),
                    evidence.toString(),
                    result.eligibility().name(), result.exclusionReason(),
                    result.oppositeStance());
            return true;
        } catch (DuplicateKeyException duplicate) {
            // The readback below is a current read (FOR UPDATE): a caller transaction whose
            // REPEATABLE READ snapshot predates the concurrent winner still observes the
            // committed row, so the replay resolves to a no-op instead of re-throwing the
            // duplicate key error.
            StoredResult stored = loadResult(engineLevel, result);
            if (stored == null) {
                throw duplicate;
            }
            if (!stored.matches(json, result, evidence)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Sentiment result is immutable: this revision/engine/algorithm/dictionary "
                                + "tuple already stores a different payload");
            }
            return false;
        }
    }

    /**
     * Writes or refreshes the window row for its own analysis version. The three counters
     * stay separate on purpose: eligible directional samples, retained mentions and
     * ambiguous exclusions are not interchangeable.
     */
    public void persistWindow(WindowResult window) {
        Objects.requireNonNull(window, "window");
        requireText(window.securityCode(), "securityCode");
        requireText(window.windowType(), "windowType");
        requireText(window.analysisVersion(), "analysisVersion");
        requireText(window.aggregationPolicyVersion(), "aggregationPolicyVersion");
        requireText(window.evidenceState(), "evidenceState");
        if (window.windowEnd() == null) {
            throw new IllegalArgumentException("windowEnd is required");
        }

        jdbc.update("""
                INSERT INTO m5_sentiment_window
                    (window_id, security_code, window_type, window_end, analysis_version,
                     aggregation_policy_version, score, eligible_sample_count, mention_count,
                     excluded_ambiguous_count, evidence_state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6)) AS incoming
                ON DUPLICATE KEY UPDATE
                    score = incoming.score,
                    eligible_sample_count = incoming.eligible_sample_count,
                    mention_count = incoming.mention_count,
                    excluded_ambiguous_count = incoming.excluded_ambiguous_count,
                    evidence_state = incoming.evidence_state
                """, UUID.randomUUID().toString(), window.securityCode(), window.windowType(),
                Timestamp.from(window.windowEnd()), window.analysisVersion(),
                window.aggregationPolicyVersion(), window.score(), window.eligibleSampleCount(),
                window.mentionCount(), window.excludedAmbiguousCount(), window.evidenceState());
    }

    private StoredResult loadResult(String engineLevel, SentimentResult result) {
        List<StoredResult> rows = jdbc.query("""
                SELECT score, confidence, unknown_reason, eligibility, exclusion_reason,
                       is_opposite_stance, hit_evidence
                FROM m5_sentiment_result
                WHERE doc_revision_id = ? AND engine_level = ?
                  AND algo_version = ? AND dict_version = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new StoredResult(
                        resultSet.getBigDecimal(1),
                        resultSet.getBigDecimal(2),
                        resultSet.getString(3),
                        resultSet.getString(4),
                        resultSet.getString(5),
                        resultSet.getBoolean(6),
                        resultSet.getString(7)),
                result.docRevisionId(), engineLevel, result.algoVersion(), result.dictVersion());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private JsonNode evidenceNode(SentimentResult result) {
        ObjectNode node = json.createObjectNode();
        var hits = node.putArray("hits");
        result.hitWords().forEach(hits::add);
        node.put("oppositeStance", result.oppositeStance());
        return node;
    }

    /** Normalised to the DECIMAL(6,2) column precision so a replay compares like with like. */
    private static BigDecimal normalizeScore(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Normalised to the DECIMAL(5,4) column precision so a replay compares like with like. */
    private static BigDecimal normalizeConfidence(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    /** The stored identity of one result; only used to reject a non-deterministic replay. */
    private record StoredResult(
            BigDecimal score,
            BigDecimal confidence,
            String unknownReason,
            String eligibility,
            String exclusionReason,
            boolean oppositeStance,
            String hitEvidence) {

        private boolean matches(ObjectMapper json, SentimentResult result, JsonNode expectedEvidence) {
            return sameNumber(score, normalizeScore(result.score()))
                    && sameNumber(confidence, normalizeConfidence(result.confidence()))
                    && Objects.equals(unknownReason, result.unknownReason())
                    && Objects.equals(eligibility, result.eligibility().name())
                    && Objects.equals(exclusionReason, result.exclusionReason())
                    && oppositeStance == result.oppositeStance()
                    && sameEvidence(json, expectedEvidence);
        }

        /**
         * JSON columns are normalised by MySQL, so the comparison parses both sides instead
         * of comparing bytes; a null stored value can never match, since every write above
         * stores an evidence object.
         */
        private boolean sameEvidence(ObjectMapper json, JsonNode expected) {
            if (hitEvidence == null) {
                return false;
            }
            try {
                return json.readTree(hitEvidence).equals(expected);
            } catch (JsonProcessingException malformed) {
                throw new IllegalStateException("Stored hit_evidence is not valid JSON", malformed);
            }
        }

        private static boolean sameNumber(BigDecimal left, BigDecimal right) {
            if (left == null || right == null) {
                return left == right;
            }
            // DECIMAL scale is fixed by the column, so compare numerically, not by scale.
            return left.compareTo(right) == 0;
        }
    }
}
