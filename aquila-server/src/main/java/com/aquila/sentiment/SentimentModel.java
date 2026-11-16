package com.aquila.sentiment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
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
 *
 * <p>Stage D review 2026-11-15 (C1/C2/I6/I7/I8/M2): the title/body weights, the confidence
 * interpretation and the aggregation weighting/decay are explicit versioned inputs with
 * no hidden arithmetic choice; aggregation samples carry the published instant and the
 * canonical content-group id so window membership and canonical dedup can be enforced
 * instead of trusted; an uncalibrated policy can never produce COMPLETE; an analysis
 * version cannot mix two policy versions; a dictionary snapshot is deeply immutable;
 * duplicate security codes are rejected rather than misread as multi-security.
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
            // TreeMap/TreeSet give a deterministic iteration order for longest-match scans;
            // the unmodifiable views keep a caller from changing one version's behaviour
            // after the fact (review I-8: a mutable snapshot breaks determinism).
            wordScores = Collections.unmodifiableSortedMap(new TreeMap<>(wordScores));
            negations = Collections.unmodifiableSortedSet(new TreeSet<>(negations));
            degreeMultipliers = Collections.unmodifiableSortedMap(new TreeMap<>(degreeMultipliers));
        }
    }

    /**
     * The confidence interpretation is itself an explicit, versioned choice (review C-1):
     * the engine refuses to compute a confidence without one. HITS_OVER_FULL_HITS is the
     * linear reading min(scoreHits / fullConfidenceHits, 1); further strategies are added
     * as enum constants only when the PO freezes them, so no interpretation is ever implicit.
     */
    public enum ConfidenceStrategy {
        HITS_OVER_FULL_HITS
    }

    /**
     * Explicit algorithm parameters. minConfidence, fullConfidenceHits, maxEvidenceHits,
     * excludeOppositeStanceMultiSecurity, titleWeight, bodyWeight and the confidence
     * strategy are PO-owned decisions (unified plan 9.1 / Stage D review C-1); the engine
     * has no defaults and refuses a snapshot without them.
     */
    public record AlgorithmSnapshot(
            String algoVersion,
            BigDecimal minConfidence,
            int fullConfidenceHits,
            int maxEvidenceHits,
            boolean excludeOppositeStanceMultiSecurity,
            BigDecimal titleWeight,
            BigDecimal bodyWeight,
            ConfidenceStrategy confidenceStrategy) {
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
            if (titleWeight == null || titleWeight.signum() <= 0
                    || bodyWeight == null || bodyWeight.signum() <= 0) {
                throw new IllegalArgumentException(
                        "titleWeight and bodyWeight must be explicit positive values");
            }
            if (confidenceStrategy == null) {
                throw new IllegalArgumentException("confidenceStrategy is an explicit versioned choice");
            }
        }
    }

    /**
     * Document content. Stage C stores only body_hash plus a raw reference, not the text,
     * so the caller reads the raw record and supplies the text here (brief section 2).
     * Duplicate security codes are rejected (review M-2): a repeated code is an upstream
     * data problem, not evidence of multi-security opposite-stance content.
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
            if (new HashSet<>(securityCodes).size() != securityCodes.size()) {
                throw new IllegalArgumentException("securityCodes must not contain duplicates");
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

    /**
     * Window types are frozen by unified plan section 9.2, not chosen here. windowEnd is
     * aligned to the 15-minute boundary grid (Asia/Shanghai is a whole-hour offset, so the
     * grid is the same in epoch seconds); an unaligned window end is rejected rather than
     * silently bucketed (Stage D review C-2).
     */
    public record WindowKey(String securityCode, String windowType, Instant windowEnd) {
        public WindowKey {
            requireText(securityCode, "securityCode");
            if (!Set.of("15m", "1h", "24h").contains(windowType)) {
                throw new IllegalArgumentException("windowType must be one of 15m/1h/24h");
            }
            if (windowEnd == null) {
                throw new IllegalArgumentException("windowEnd is required");
            }
            if (windowEnd.getNano() != 0 || windowEnd.getEpochSecond() % 900 != 0) {
                throw new IllegalArgumentException(
                        "windowEnd must be aligned to the 15-minute boundary grid");
            }
        }

        /** Half-open window length in seconds: 15m, 1h or 24h. */
        public long durationSeconds() {
            return switch (windowType) {
                case "15m" -> 900L;
                case "1h" -> 3_600L;
                default -> 86_400L;
            };
        }
    }

    /**
     * One aggregation input. publishedAt is the business publication instant used for
     * window membership; canonicalGroupId identifies the canonical content group so a
     * repeated propagation of the same content is deduplicated instead of counted twice
     * (Stage D review C-2). Both are mandatory: a sample that cannot be placed in time
     * and deduplicated cannot be safely aggregated.
     */
    public record AggregationSample(
            String docRevisionId,
            List<String> securityCodes,
            BigDecimal score,
            BigDecimal confidence,
            int mentionCount,
            Eligibility eligibility,
            String exclusionReason,
            boolean oppositeStance,
            Instant publishedAt,
            String canonicalGroupId) {
        public AggregationSample {
            requireText(docRevisionId, "docRevisionId");
            securityCodes = securityCodes == null ? List.of() : List.copyOf(securityCodes);
            if (mentionCount < 0) {
                throw new IllegalArgumentException("mentionCount must not be negative");
            }
            if (publishedAt == null) {
                throw new IllegalArgumentException("publishedAt is required for window membership");
            }
            requireText(canonicalGroupId, "canonicalGroupId");
        }
    }

    /**
     * How a sample contributes to the directional mean. UNIFORM is the only interpretation
     * the mechanism currently implements; quality/interaction/source weights become a
     * further constant only when the PO freezes them (unified plan 9.2), so equal weight
     * is an explicit versioned choice rather than a hidden default.
     */
    public enum SampleWeighting {
        UNIFORM
    }

    /**
     * Explicit, versioned decay choice. NONE means no time decay (an explicit decision,
     * not an implicit default); EXPONENTIAL_HALF_LIFE weights a sample by
     * {@code 0.5^(age / halfLifeSeconds)} relative to the window end.
     *
     * <p>{@code weightScale} is the explicit decimal precision of the computed weight
     * (review 2026-11-16 Important-1): the engine uses {@link StrictMath#pow}, which is
     * bit-reproducible across JVMs, and rounds to this scale with HALF_UP. Carrying the
     * scale in the versioned policy means two nodes running the same policy version cannot
     * disagree on a window score because of an implicit {@code MathContext}. It is required
     * for every decay type so the arithmetic contract is part of the version identity.
     */
    public record DecayPolicy(DecayType type, Long halfLifeSeconds, int weightScale) {
        public enum DecayType { NONE, EXPONENTIAL_HALF_LIFE }
        public DecayPolicy {
            if (type == null) {
                throw new IllegalArgumentException("Decay type is an explicit versioned choice");
            }
            if (weightScale < 0 || weightScale > 18) {
                throw new IllegalArgumentException("weightScale must be an explicit 0..18 value");
            }
            if (type == DecayType.NONE && halfLifeSeconds != null) {
                throw new IllegalArgumentException("No decay must not carry a half-life");
            }
            if (type == DecayType.EXPONENTIAL_HALF_LIFE
                    && (halfLifeSeconds == null || halfLifeSeconds <= 0)) {
                throw new IllegalArgumentException("Exponential decay needs a positive half-life");
            }
        }
    }

    /**
     * The aggregation policy snapshot. An uncalibrated policy can never label its output
     * COMPLETE (Stage D review I-6): the requireCalibratedForComplete bypass switch was
     * removed, so calibrated alone decides between COMPLETE and EXPERIMENTAL.
     */
    public record AggregationPolicy(
            String policyVersion,
            boolean calibrated,
            SampleWeighting sampleWeighting,
            DecayPolicy decay) {
        public AggregationPolicy {
            requireText(policyVersion, "policyVersion");
            if (sampleWeighting == null) {
                throw new IllegalArgumentException("sampleWeighting is an explicit versioned choice");
            }
            if (decay == null) {
                throw new IllegalArgumentException("decay is an explicit versioned choice");
            }
        }
    }

    /**
     * A versioned input snapshot for rebuild(). V7 (Task 2) owns the persisted
     * analysis-version state, watermarks and active pointer; this record is the explicit
     * input that makes the aggregation a deterministic pure function. The policy version
     * recorded on the analysis version must equal the policy's own version (review I-7):
     * attributing one policy's computation to another version's identity is refused.
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
            if (!aggregationPolicyVersion.equals(policy.policyVersion())) {
                throw new IllegalArgumentException(
                        "aggregationPolicyVersion must match the policy snapshot version");
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
