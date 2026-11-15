package com.aquila.sentiment;

import com.aquila.sentiment.SentimentModel.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Version-keyed window aggregation over explicit versioned samples.
 *
 * <p>Three counters are deliberately distinct (plan Step 4): a mention is retained even
 * when the sample is excluded from directional aggregation; an ambiguous opposite-stance
 * multi-security sample is excluded with a persisted reason while a plain EXCLUDED sample
 * is not miscounted as ambiguous; an unknown sample is neither eligible nor turned into a
 * neutral zero.
 *
 * <p>Stage D review 2026-11-15 (C-1/C-2/M-1): window membership is enforced here, not
 * trusted from the caller — a sample belongs to the window iff
 * {@code windowEnd - duration <= publishedAt < windowEnd} (half-open, frozen by the
 * spec). Repeated propagation of the same canonical content group counts once, whichever
 * revision of the group arrives first lexicographically. The directional mean consumes
 * the policy's explicit weighting and decay (no hidden arithmetic mean and no hidden
 * "no decay" default), and an uncalibrated policy can only produce EXPERIMENTAL
 * evidence (unified plan section 9.2).
 */
public final class AggregationService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MINUS_HUNDRED = new BigDecimal("-100");

    public WindowResult rebuild(WindowKey key, AnalysisVersion version) {
        int mentionCount = 0;
        int eligibleSampleCount = 0;
        int excludedAmbiguousCount = 0;
        BigDecimal weightedTotal = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        List<String> exclusionReasons = new ArrayList<>();
        Set<String> seenCanonicalGroups = new HashSet<>();

        Instant windowStart = key.windowEnd().minusSeconds(key.durationSeconds());
        // First pass: one representative per canonical group inside the window — the
        // lexicographically smallest revision id of the group, whatever the arrival order.
        java.util.Map<String, AggregationSample> representatives = new java.util.HashMap<>();
        for (AggregationSample sample : version.samples()) {
            if (!sample.securityCodes().contains(key.securityCode())) {
                continue;
            }
            if (sample.publishedAt().isBefore(windowStart)
                    || !sample.publishedAt().isBefore(key.windowEnd())) {
                continue;
            }
            AggregationSample current = representatives.get(sample.canonicalGroupId());
            if (current == null || sample.docRevisionId().compareTo(current.docRevisionId()) < 0) {
                representatives.put(sample.canonicalGroupId(), sample);
            }
        }
        for (java.util.Map.Entry<String, AggregationSample> entry : representatives.entrySet()) {
            seenCanonicalGroups.add(entry.getKey());
            AggregationSample sample = entry.getValue();
            mentionCount += sample.mentionCount();
            if (sample.eligibility() != Eligibility.ELIGIBLE) {
                if (sample.eligibility() == Eligibility.AMBIGUOUS_MULTI_SECURITY) {
                    excludedAmbiguousCount++;
                }
                if (sample.exclusionReason() != null && !sample.exclusionReason().isBlank()) {
                    exclusionReasons.add(sample.exclusionReason());
                }
                continue;
            }
            if (sample.score() == null) {
                // Unknown is NULL, never zero; it contributes a mention but no direction.
                continue;
            }
            BigDecimal weight = weightOf(version.policy(), key, sample);
            eligibleSampleCount++;
            weightedTotal = weightedTotal.add(weight.multiply(sample.score()));
            weightSum = weightSum.add(weight);
        }

        BigDecimal score = weightSum.signum() == 0
                ? null
                : weightedTotal.divide(weightSum, 2, RoundingMode.HALF_UP)
                        .max(MINUS_HUNDRED)
                        .min(HUNDRED);
        String evidenceState = version.policy().calibrated() ? "COMPLETE" : "EXPERIMENTAL";

        return new WindowResult(
                key.securityCode(),
                key.windowType(),
                key.windowEnd(),
                version.versionId(),
                version.aggregationPolicyVersion(),
                score,
                eligibleSampleCount,
                mentionCount,
                excludedAmbiguousCount,
                evidenceState,
                exclusionReasons);
    }

    /**
     * The policy's explicit weighting and decay. UNIFORM weighting is 1.0 (an explicit
     * versioned choice); exponential decay scales the weight by
     * {@code 0.5^(age / halfLifeSeconds)} relative to the window end, where age is
     * clamped at zero so a sample at the window end keeps its full weight.
     */
    private BigDecimal weightOf(AggregationPolicy policy, WindowKey key, AggregationSample sample) {
        if (policy.sampleWeighting() != SampleWeighting.UNIFORM) {
            throw new IllegalArgumentException(
                    "Unsupported weighting: " + policy.sampleWeighting());
        }
        if (policy.decay().type() == DecayPolicy.DecayType.NONE) {
            return BigDecimal.ONE;
        }
        long ageSeconds = Math.max(0,
                Duration.between(sample.publishedAt(), key.windowEnd()).toSeconds());
        double exponent = (double) ageSeconds / policy.decay().halfLifeSeconds();
        return new BigDecimal(Math.pow(0.5, exponent), new MathContext(12));
    }
}
