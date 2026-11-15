package com.aquila.sentiment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Explicit, versioned inputs and results for Stage D Task 1.
 *
 * <p>Nothing here embeds a business dictionary, a threshold, a weight or an eligibility
 * default. Unified plan section 9.1/9.2 requires those to be frozen by the PO before a
 * dictionary can be published; until then every one of them is supplied by the caller as
 * a versioned snapshot and a missing value fails closed. This class is the mechanism
 * layer only — see .superpowers/sdd/2026-11-09-zero-based-stage-d/task-1-brief.md.
 */
public final class SentimentModel {

    private SentimentModel() {}

    /** An immutable dictionary snapshot. wordScores maps a term to its signed base score. */
    public record DictionarySnapshot(
            String dictVersion,
            Map<String, BigDecimal> wordScores,
            Set<String> negations,
            Map<String, BigDecimal> degreeMultipliers,
            boolean calibrated) {
        public DictionarySnapshot {
            requireText(dictVersion, "dictVersion");
            if (wordScores == null || negations == null || degreeMultipliers == null) {
                throw new IllegalArgumentException("Dictionary snapshot inputs must not be null");
            }
            // TreeMap/TreeSet give a deterministic iteration order for longest-match scans.
            wordScores = new TreeMap<>(wordScores);
            negations = new TreeSet<>(negations);
            degreeMultipliers = new TreeMap<>(degreeMultipliers);
        }
    }

    /**
     * Explicit algorithm parameters. minConfidence, fullConfidenceHits, maxEvidenceHits and
     * excludeOppositeStanceMultiSecurity are PO-owned decisions; the engine has no defaults.
     */
    public record AlgorithmSnapshot(
            String algoVersion,
            BigDecimal minConfidence,
            int fullConfidenceHits,
            int maxEvidenceHits,
            boolean excludeOppositeStanceMultiSecurity) {
        public AlgorithmSnapshot {
            requireText(algoVersion, "algoVersion");
            if (minConfidence == null
                    || minConfidence.signum() < 0
                    || minConfidence.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("minConfidence must be an explicit value in [0,1]");
            }
            if (fullConfidenceHits <= 0 || maxEvidenceHits < 0) {
                throw new IllegalArgumentException("Explicit hit bounds must be positive");
            }
        }
    }

    /**
     * Document content. Stage C stores only body_hash plus a raw reference, not the text,
     * so the caller reads the raw record and supplies the text here (brief section 2).
     */
    public record SentimentInput(
            String docRevisionId,
            String title,
            String body,
            List<String> securityCodes) {
        public SentimentInput {
            requireText(docRevisionId, "docRevisionId");
            title = title == null ? "" : title;
            body = body == null ? "" : body;
            if (securityCodes == null) {
                throw new IllegalArgumentException("securityCodes must be explicit (may be empty)");
            }
            securityCodes = List.copyOf(securityCodes);
        }
    }

    public enum Eligibility {
        ELIGIBLE,
        AMBIGUOUS_MULTI_SECURITY,
        EXCLUDED
    }

    /** score/confidence are NULL for unknown, never zero. */
    public record SentimentResult(
            String docRevisionId,
            String algoVersion,
            String dictVersion,
            BigDecimal score,
            BigDecimal confidence,
            String unknownReason,
            Eligibility eligibility,
            String exclusionReason,
            List<String> hitWords,
            boolean oppositeStance) {
        public SentimentResult {
            hitWords = hitWords == null ? List.of() : List.copyOf(hitWords);
        }
    }

    /** Window types are frozen by unified plan section 9.2, not chosen here. */
    public record WindowKey(String securityCode, String windowType, Instant windowEnd) {
        public WindowKey {
            requireText(securityCode, "securityCode");
            if (!Set.of("15m", "1h", "24h").contains(windowType)) {
                throw new IllegalArgumentException("windowType must be one of 15m/1h/24h");
            }
            if (windowEnd == null) {
                throw new IllegalArgumentException("windowEnd is required");
            }
        }
    }

    public record AggregationSample(
            String docRevisionId,
            List<String> securityCodes,
            BigDecimal score,
            BigDecimal confidence,
            int mentionCount,
            Eligibility eligibility,
            String exclusionReason,
            boolean oppositeStance) {
        public AggregationSample {
            securityCodes = securityCodes == null ? List.of() : List.copyOf(securityCodes);
            if (mentionCount < 0) {
                throw new IllegalArgumentException("mentionCount must not be negative");
            }
        }
    }

    /** An uncalibrated policy must not present its output as a complete, calibrated fact. */
    public record AggregationPolicy(
            String policyVersion,
            boolean calibrated,
            boolean requireCalibratedForComplete) {
        public AggregationPolicy {
            requireText(policyVersion, "policyVersion");
        }
    }

    /**
     * A versioned input snapshot for rebuild(). V6 (Task 2) owns the persisted
     * analysis-version state, watermarks and active pointer; this record is the explicit
     * input that makes the aggregation a deterministic pure function.
     */
    public record AnalysisVersion(
            String versionId,
            String aggregationPolicyVersion,
            AggregationPolicy policy,
            List<AggregationSample> samples) {
        public AnalysisVersion {
            requireText(versionId, "versionId");
            requireText(aggregationPolicyVersion, "aggregationPolicyVersion");
            if (policy == null || samples == null) {
                throw new IllegalArgumentException("Policy and samples are explicit inputs");
            }
            samples = List.copyOf(samples);
        }
    }

    public record WindowResult(
            String securityCode,
            String windowType,
            Instant windowEnd,
            String analysisVersion,
            String aggregationPolicyVersion,
            BigDecimal score,
            int eligibleSampleCount,
            int mentionCount,
            int excludedAmbiguousCount,
            String evidenceState,
            List<String> exclusionReasons) {
        public WindowResult {
            exclusionReasons = exclusionReasons == null ? List.of() : List.copyOf(exclusionReasons);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
