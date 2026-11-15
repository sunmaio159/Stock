package com.aquila.sentiment;

import com.aquila.sentiment.SentimentModel.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Version-keyed window aggregation over explicit versioned samples.
 *
 * <p>Three counters are deliberately distinct (plan Step 4): a mention is retained even
 * when the sample is excluded from directional aggregation; an ambiguous opposite-stance
 * multi-security sample is excluded with a persisted reason; an unknown sample is neither
 * eligible nor turned into a neutral zero. No quality, interaction, source weight or decay
 * is invented here — those are frozen by the PO in aggregation_policy_version, so an
 * uncalibrated policy can only produce EXPERIMENTAL evidence (unified plan section 9.2).
 */
public final class AggregationService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MINUS_HUNDRED = new BigDecimal("-100");

    public WindowResult rebuild(WindowKey key, AnalysisVersion version) {
        int mentionCount = 0;
        int eligibleSampleCount = 0;
        int excludedAmbiguousCount = 0;
        BigDecimal total = BigDecimal.ZERO;
        List<String> exclusionReasons = new ArrayList<>();

        for (AggregationSample sample : version.samples()) {
            if (!sample.securityCodes().contains(key.securityCode())) {
                continue;
            }
            mentionCount += sample.mentionCount();
            if (sample.eligibility() != Eligibility.ELIGIBLE) {
                excludedAmbiguousCount++;
                if (sample.exclusionReason() != null && !sample.exclusionReason().isBlank()) {
                    exclusionReasons.add(sample.exclusionReason());
                }
                continue;
            }
            if (sample.score() == null) {
                // Unknown is NULL, never zero; it contributes a mention but no direction.
                continue;
            }
            eligibleSampleCount++;
            total = total.add(sample.score());
        }

        BigDecimal score = eligibleSampleCount == 0
                ? null
                : total.divide(BigDecimal.valueOf(eligibleSampleCount), 2, RoundingMode.HALF_UP)
                        .max(MINUS_HUNDRED)
                        .min(HUNDRED);
        String evidenceState = version.policy().requireCalibratedForComplete()
                && !version.policy().calibrated()
                ? "EXPERIMENTAL"
                : "COMPLETE";

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
}
