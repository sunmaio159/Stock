package com.aquila.sentiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquila.sentiment.SentimentModel.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Falsifying tests for Stage D Task 1 aggregation: unknown must stay NULL, ambiguous
 * opposite-stance multi-security content is kept as a mention but excluded from
 * directional stock aggregation with a persisted reason, and the three counters are
 * distinct (plan Step 4 / Review Focus).
 */
class AggregationEligibilityTest {

    private static final Instant WINDOW_END = Instant.parse("2026-11-15T03:15:00Z");

    private final AggregationService aggregation = new AggregationService();

    private static WindowKey key() {
        return new WindowKey("SH600000", "15m", WINDOW_END);
    }

    private static AnalysisVersion version(boolean calibrated, List<AggregationSample> samples) {
        return new AnalysisVersion(
                "av-1",
                "policy-1",
                new AggregationPolicy("policy-1", calibrated, true),
                samples);
    }

    private static AggregationSample eligible(String revisionId, String score) {
        return new AggregationSample(
                revisionId,
                List.of("SH600000"),
                new BigDecimal(score),
                new BigDecimal("0.90"),
                1,
                Eligibility.ELIGIBLE,
                null,
                false);
    }

    private static AggregationSample ambiguous() {
        return new AggregationSample(
                "rev-ambiguous",
                List.of("SH600000", "SZ000001"),
                new BigDecimal("50"),
                new BigDecimal("0.90"),
                1,
                Eligibility.AMBIGUOUS_MULTI_SECURITY,
                "Opposite-stance content mentions multiple securities",
                true);
    }

    private static AggregationSample unknown() {
        return new AggregationSample(
                "rev-unknown", List.of("SH600000"), null, null, 1, Eligibility.ELIGIBLE, null, false);
    }

    @Test
    void oppositeStanceMultiSecurityIsExcludedButRetainedAsMention() {
        WindowResult result = aggregation.rebuild(key(), version(true, List.of(ambiguous())));

        assertThat(result.excludedAmbiguousCount()).isEqualTo(1);
        assertThat(result.eligibleSampleCount()).isZero();
        assertThat(result.mentionCount()).isEqualTo(1);
        assertThat(result.score()).isNull();
        assertThat(result.exclusionReasons()).isNotEmpty();
    }

    @Test
    void singleSecurityOppositeStanceStaysEligible() {
        AggregationSample single = new AggregationSample(
                "rev-single",
                List.of("SH600000"),
                new BigDecimal("30"),
                new BigDecimal("0.90"),
                1,
                Eligibility.ELIGIBLE,
                null,
                true);

        WindowResult result = aggregation.rebuild(key(), version(true, List.of(single)));

        assertThat(result.eligibleSampleCount()).isEqualTo(1);
        assertThat(result.excludedAmbiguousCount()).isZero();
    }

    @Test
    void unknownSampleNeverBecomesNeutralZero() {
        WindowResult result = aggregation.rebuild(key(), version(true, List.of(unknown())));

        assertThat(result.score()).isNull();
        assertThat(result.score()).isNotEqualTo(BigDecimal.ZERO);
        assertThat(result.eligibleSampleCount()).isZero();
        assertThat(result.mentionCount()).isEqualTo(1);
    }

    @Test
    void eligibleMentionAndExcludedCountsAreDistinct() {
        WindowResult result = aggregation.rebuild(
                key(), version(true, List.of(eligible("rev-1", "-20"), ambiguous(), unknown())));

        assertThat(result.eligibleSampleCount()).isEqualTo(1);
        assertThat(result.mentionCount()).isEqualTo(3);
        assertThat(result.excludedAmbiguousCount()).isEqualTo(1);
        assertThat(result.score()).isEqualByComparingTo("-20");
    }

    @Test
    void sampleForAnotherSecurityDoesNotEnterTheWindow() {
        AggregationSample other = new AggregationSample(
                "rev-other",
                List.of("SZ000001"),
                new BigDecimal("80"),
                new BigDecimal("0.90"),
                1,
                Eligibility.ELIGIBLE,
                null,
                false);

        WindowResult result = aggregation.rebuild(key(), version(true, List.of(other)));

        assertThat(result.mentionCount()).isZero();
        assertThat(result.eligibleSampleCount()).isZero();
        assertThat(result.score()).isNull();
    }

    @Test
    void uncalibratedPolicyIsExperimentalNotComplete() {
        WindowResult experimental = aggregation.rebuild(key(), version(false, List.of(eligible("rev-1", "20"))));
        WindowResult complete = aggregation.rebuild(key(), version(true, List.of(eligible("rev-1", "20"))));

        assertThat(experimental.evidenceState()).isEqualTo("EXPERIMENTAL");
        assertThat(complete.evidenceState()).isEqualTo("COMPLETE");
    }
}
