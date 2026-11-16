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
 *
 * <p>Stage D review 2026-11-15 (C-1/C-2/M-1): window membership [E-duration, E) is
 * enforced here rather than trusted; the same canonical content group counts once; an
 * unaligned window end is refused; explicit decay changes the directional mean; a plain
 * EXCLUDED sample is retained as a mention with its reason but not miscounted as
 * ambiguous; and an uncalibrated policy can never label output COMPLETE.
 */
class AggregationEligibilityTest {

    private static final Instant WINDOW_END = Instant.parse("2026-11-15T03:15:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-11-15T03:00:00Z");

    private final AggregationService aggregation = new AggregationService();

    private static WindowKey key() {
        return new WindowKey("SH600000", "15m", WINDOW_END);
    }

    private static AnalysisVersion version(boolean calibrated, List<AggregationSample> samples) {
        return new AnalysisVersion(
                "av-1",
                "policy-1",
                new AggregationPolicy(
                        "policy-1", calibrated, SampleWeighting.UNIFORM,
                        new DecayPolicy(DecayPolicy.DecayType.NONE, null, 12)),
                samples);
    }

    private static AggregationSample eligible(
            String revisionId, String score, Instant publishedAt) {
        return new AggregationSample(
                revisionId, List.of("SH600000"), new BigDecimal(score),
                new BigDecimal("0.90"), 1, Eligibility.ELIGIBLE, null, false,
                publishedAt, revisionId);
    }

    private static AggregationSample ambiguous(Instant publishedAt) {
        return new AggregationSample(
                "rev-ambiguous", List.of("SH600000", "SZ000001"), new BigDecimal("50"),
                new BigDecimal("0.90"), 1, Eligibility.AMBIGUOUS_MULTI_SECURITY,
                "Opposite-stance content mentions multiple securities", true,
                publishedAt, "rev-ambiguous");
    }

    private static AggregationSample unknown(Instant publishedAt) {
        return new AggregationSample(
                "rev-unknown", List.of("SH600000"), null, null, 1,
                Eligibility.ELIGIBLE, null, false, publishedAt, "rev-unknown");
    }

    @Test
    void oppositeStanceMultiSecurityIsExcludedButRetainedAsMention() {
        WindowResult result = aggregation.rebuild(
                key(), version(true, List.of(ambiguous(WINDOW_START))));

        assertThat(result.excludedAmbiguousCount()).isEqualTo(1);
        assertThat(result.eligibleSampleCount()).isZero();
        assertThat(result.mentionCount()).isEqualTo(1);
        assertThat(result.score()).isNull();
        assertThat(result.exclusionReasons()).isNotEmpty();
    }

    @Test
    void singleSecurityOppositeStanceStaysEligible() {
        AggregationSample single = new AggregationSample(
                "rev-single", List.of("SH600000"), new BigDecimal("30"),
                new BigDecimal("0.90"), 1, Eligibility.ELIGIBLE, null, true,
                WINDOW_START, "rev-single");

        WindowResult result = aggregation.rebuild(key(), version(true, List.of(single)));

        assertThat(result.eligibleSampleCount()).isEqualTo(1);
        assertThat(result.excludedAmbiguousCount()).isZero();
    }

    @Test
    void unknownSampleNeverBecomesNeutralZero() {
        WindowResult result = aggregation.rebuild(
                key(), version(true, List.of(unknown(WINDOW_START))));

        assertThat(result.score()).isNull();
        assertThat(result.score()).isNotEqualTo(BigDecimal.ZERO);
        assertThat(result.eligibleSampleCount()).isZero();
        assertThat(result.mentionCount()).isEqualTo(1);
    }

    @Test
    void eligibleMentionAndExcludedCountsAreDistinct() {
        WindowResult result = aggregation.rebuild(key(), version(true, List.of(
                eligible("rev-1", "-20", WINDOW_START),
                ambiguous(WINDOW_START),
                unknown(WINDOW_START))));

        assertThat(result.eligibleSampleCount()).isEqualTo(1);
        assertThat(result.mentionCount()).isEqualTo(3);
        assertThat(result.excludedAmbiguousCount()).isEqualTo(1);
        assertThat(result.score()).isEqualByComparingTo("-20");
    }

    @Test
    void sampleForAnotherSecurityDoesNotEnterTheWindow() {
        AggregationSample other = new AggregationSample(
                "rev-other", List.of("SZ000001"), new BigDecimal("80"),
                new BigDecimal("0.90"), 1, Eligibility.ELIGIBLE, null, false,
                WINDOW_START, "rev-other");

        WindowResult result = aggregation.rebuild(key(), version(true, List.of(other)));

        assertThat(result.mentionCount()).isZero();
        assertThat(result.eligibleSampleCount()).isZero();
        assertThat(result.score()).isNull();
    }

    @Test
    void uncalibratedPolicyIsExperimentalNotComplete() {
        WindowResult experimental = aggregation.rebuild(
                key(), version(false, List.of(eligible("rev-1", "20", WINDOW_START))));
        WindowResult complete = aggregation.rebuild(
                key(), version(true, List.of(eligible("rev-1", "20", WINDOW_START))));

        assertThat(experimental.evidenceState()).isEqualTo("EXPERIMENTAL");
        assertThat(complete.evidenceState()).isEqualTo("COMPLETE");
    }

    /**
     * Review C-2: window membership is [E-duration, E). The lower bound is inclusive; a
     * sample exactly at E-duration belongs. Without the membership filter every sample
     * enters every window and this test cannot distinguish any boundaries.
     */
    @Test
    void theWindowLowerBoundIsInclusive() {
        WindowResult result = aggregation.rebuild(key(), version(true,
                List.of(eligible("rev-boundary", "20", WINDOW_START))));

        assertThat(result.eligibleSampleCount()).isEqualTo(1);
        assertThat(result.score()).isEqualByComparingTo("20.00");
    }

    /** Review C-2: the upper bound E is exclusive; a late sample stays out. */
    @Test
    void theWindowUpperBoundIsExclusive() {
        WindowResult result = aggregation.rebuild(key(), version(true,
                List.of(eligible("rev-late", "20", WINDOW_END),
                        eligible("rev-in", "40", Instant.parse("2026-11-15T03:14:59Z")))));

        assertThat(result.eligibleSampleCount()).isEqualTo(1);
        assertThat(result.score()).isEqualByComparingTo("40.00");
    }

    /** Review C-2: a sample older than the window is not counted at all. */
    @Test
    void aSampleBeforeTheWindowStaysOut() {
        WindowResult result = aggregation.rebuild(key(), version(true,
                List.of(eligible("rev-old", "20", Instant.parse("2026-11-15T02:59:59Z")))));

        assertThat(result.mentionCount()).isZero();
        assertThat(result.eligibleSampleCount()).isZero();
        assertThat(result.score()).isNull();
    }

    /**
     * Review C-2: two revisions of the same canonical content group count once — the
     * lexicographically smallest revision id represents the group. Red against a rebuild
     * that iterates samples naively (both counted, score 30.00, mentions 2).
     */
    @Test
    void duplicateCanonicalPropagationIsCountedOnce() {
        AggregationSample firstCopy = new AggregationSample(
                "rev-b", List.of("SH600000"), new BigDecimal("40"),
                new BigDecimal("0.90"), 1, Eligibility.ELIGIBLE, null, false,
                WINDOW_START, "canonical-post-1");
        AggregationSample secondCopy = new AggregationSample(
                "rev-a", List.of("SH600000"), new BigDecimal("20"),
                new BigDecimal("0.90"), 1, Eligibility.ELIGIBLE, null, false,
                WINDOW_START, "canonical-post-1");

        WindowResult result = aggregation.rebuild(
                key(), version(true, List.of(firstCopy, secondCopy)));

        assertThat(result.eligibleSampleCount()).isEqualTo(1);
        assertThat(result.mentionCount()).isEqualTo(1);
        assertThat(result.score()).isEqualByComparingTo("20.00");
    }

    /**
     * Review M-1: a plain EXCLUDED sample keeps its mention and its persisted reason but
     * is not miscounted as ambiguous; excluded_ambiguous_count names only the ambiguous
     * shape.
     */
    @Test
    void excludedSampleIsRetainedWithoutInflatingTheAmbiguousCount() {
        AggregationSample excluded = new AggregationSample(
                "rev-excluded", List.of("SH600000"), new BigDecimal("20"),
                new BigDecimal("0.90"), 1, Eligibility.EXCLUDED,
                "Withheld by an explicit rule", false, WINDOW_START, "rev-excluded");

        WindowResult result = aggregation.rebuild(key(), version(true, List.of(excluded)));

        assertThat(result.mentionCount()).isEqualTo(1);
        assertThat(result.excludedAmbiguousCount()).isZero();
        assertThat(result.exclusionReasons()).containsExactly("Withheld by an explicit rule");
        assertThat(result.score()).isNull();
    }

    /**
     * Review C-1: explicit exponential decay must change the directional mean. The older
     * sample carries the high score, so decay drags the mean below the unweighted mean.
     */
    @Test
    void explicitDecayChangesTheDirectionalMean() {
        AggregationSample oldSample = eligible("rev-old", "80",
                Instant.parse("2026-11-15T03:00:00Z"));
        AggregationSample freshSample = eligible("rev-new", "20",
                Instant.parse("2026-11-15T03:14:00Z"));

        AggregationPolicy noDecay = new AggregationPolicy(
                "policy-1", true, SampleWeighting.UNIFORM,
                new DecayPolicy(DecayPolicy.DecayType.NONE, null, 12));
        AggregationPolicy decayed = new AggregationPolicy(
                "policy-1", true, SampleWeighting.UNIFORM,
                new DecayPolicy(DecayPolicy.DecayType.EXPONENTIAL_HALF_LIFE, 900L, 12));

        WindowResult unweighted = aggregation.rebuild(key(), new AnalysisVersion(
                "av-plain", "policy-1", noDecay, List.of(oldSample, freshSample)));
        WindowResult weighted = aggregation.rebuild(key(), new AnalysisVersion(
                "av-decay", "policy-1", decayed, List.of(oldSample, freshSample)));

        assertThat(unweighted.score()).isEqualByComparingTo("50.00");
        // Review 2026-11-16 Important-1: pin the exact reproducible value, not just the
        // direction. Weights are 0.500000000000 (age 900s) and 0.954841603910 (age 60s)
        // at halfLife=900 and weightScale=12; StrictMath.pow + explicit scale fix 40.62
        // across JVMs, while an implementation that changed the arithmetic would go red.
        assertThat(weighted.score()).isEqualByComparingTo("40.62");
    }

    /** Review C-2: an unaligned window end is refused before any aggregation runs. */
    @Test
    void anUnalignedWindowEndIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WindowKey("SH600000", "15m",
                        Instant.parse("2026-11-15T03:07:00Z")));
    }
}
