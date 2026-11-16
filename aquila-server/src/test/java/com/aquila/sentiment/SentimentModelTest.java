package com.aquila.sentiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.sentiment.SentimentModel.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Constructor-level contracts for the Stage D model (review 2026-11-15):
 * I-7 (one analysis version cannot mix two policy versions), I-8 (a dictionary snapshot
 * is deeply immutable), M-2 (duplicate security codes are rejected), plus the explicit
 * versioned-input validation added for C-1 (weights, confidence strategy, decay) and the
 * C-2 window alignment rule.
 */
class SentimentModelTest {

    private static final Instant WINDOW_END = Instant.parse("2026-11-15T03:15:00Z");

    @Test
    void dictionarySnapshotIsDeeplyImmutable() {
        Map<String, BigDecimal> source = new LinkedHashMap<>();
        source.put("gain", new BigDecimal("40"));
        Set<String> sourceNegations = new java.util.HashSet<>(Set.of("not"));
        Map<String, BigDecimal> sourceDegrees = new TreeMap<>();
        sourceDegrees.put("sharply", new BigDecimal("1.5"));

        DictionarySnapshot snapshot = new DictionarySnapshot(
                "dict-1", source, sourceNegations, sourceDegrees, false);

        // Mutating the source after construction must not change the frozen snapshot.
        source.put("gain", new BigDecimal("-100"));
        source.put("decline", new BigDecimal("-40"));
        sourceNegations.add("never");
        sourceDegrees.put("slightly", new BigDecimal("0.5"));

        assertThat(snapshot.wordScores()).hasSize(1);
        assertThat(snapshot.wordScores().get("gain")).isEqualByComparingTo("40");
        assertThat(snapshot.negations()).containsExactly("not");
        assertThat(snapshot.degreeMultipliers()).containsOnlyKeys("sharply");

        // Mutating through the accessors must be refused.
        assertThatThrownBy(() -> snapshot.wordScores().put("new", BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.negations().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.degreeMultipliers().put("new", BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void analysisVersionRefusesAMismatchedPolicyVersion() {
        AggregationPolicy policy = SentimentFixture.policy(false);

        assertThatThrownBy(() -> new AnalysisVersion(
                "av-1", "policy-of-another-version", policy, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");
    }

    @Test
    void duplicateSecurityCodesAreRejectedAsUpstreamNoise() {
        assertThatThrownBy(() -> new SentimentInput(
                "rev-1", "", "text", List.of("SH600000", "SH600000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void algorithmSnapshotRequiresExplicitWeightsAndConfidenceStrategy() {
        assertThatThrownBy(() -> new AlgorithmSnapshot(
                "algo-x", new BigDecimal("0.10"), 2, 20, true,
                null, BigDecimal.ONE, ConfidenceStrategy.HITS_OVER_FULL_HITS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("titleWeight");
        assertThatThrownBy(() -> new AlgorithmSnapshot(
                "algo-x", new BigDecimal("0.10"), 2, 20, true,
                BigDecimal.ONE, BigDecimal.ZERO, ConfidenceStrategy.HITS_OVER_FULL_HITS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("titleWeight");
        assertThatThrownBy(() -> new AlgorithmSnapshot(
                "algo-x", new BigDecimal("0.10"), 2, 20, true,
                BigDecimal.ONE, BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidenceStrategy");
    }

    @Test
    void decayPolicyMustBeSelfConsistent() {
        assertThatThrownBy(() -> new DecayPolicy(DecayPolicy.DecayType.NONE, 900L, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("half-life");
        assertThatThrownBy(() -> new DecayPolicy(DecayPolicy.DecayType.EXPONENTIAL_HALF_LIFE, null, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("half-life");
        assertThatThrownBy(() -> new DecayPolicy(DecayPolicy.DecayType.EXPONENTIAL_HALF_LIFE, 0L, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("half-life");
        assertThatThrownBy(() -> new DecayPolicy(null, null, 12))
                .isInstanceOf(IllegalArgumentException.class);
        // Review round-4 M-1: the explicit 0..18 scale guard is new code and must fail red
        // at both ends if someone removes it.
        assertThatThrownBy(() -> new DecayPolicy(DecayPolicy.DecayType.NONE, null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weightScale");
        assertThatThrownBy(() -> new DecayPolicy(
                DecayPolicy.DecayType.EXPONENTIAL_HALF_LIFE, 900L, 19))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weightScale");
    }

    @Test
    void aggregationSampleRequiresPublishedAtAndCanonicalGroup() {
        AggregationSample base = SentimentFixture.sample("rev-1", "20");

        assertThatThrownBy(() -> new AggregationSample(
                base.docRevisionId(), base.securityCodes(), base.score(), base.confidence(),
                base.mentionCount(), base.eligibility(), base.exclusionReason(),
                base.oppositeStance(), null, base.canonicalGroupId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publishedAt");
        assertThatThrownBy(() -> new AggregationSample(
                base.docRevisionId(), base.securityCodes(), base.score(), base.confidence(),
                base.mentionCount(), base.eligibility(), base.exclusionReason(),
                base.oppositeStance(), base.publishedAt(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonicalGroupId");
    }

    /** Review C-2: an unaligned window end is rejected, not silently bucketed. */
    @Test
    void windowKeyRejectsAnUnalignedWindowEnd() {
        assertThatThrownBy(() -> new WindowKey("SH600000", "15m",
                Instant.parse("2026-11-15T03:07:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aligned");
        assertThatThrownBy(() -> new WindowKey("SH600000", "15m",
                Instant.parse("2026-11-15T03:15:00.500Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aligned");
        // The 15-minute grid itself stays valid for every window type.
        assertThat(new WindowKey("SH600000", "24h", WINDOW_END).durationSeconds()).isEqualTo(86_400L);
    }

    // ---- explicit inputs for PO decisions #4 (contrast) and #6 (differentiated weights) ----

    @Test
    void contrastPolicyRequiresExplicitStrategyAndDiscountInRange() {
        assertThatThrownBy(() -> new ContrastPolicy(null, new BigDecimal("0.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy");
        assertThatThrownBy(() -> new ContrastPolicy(
                ContrastPolicy.ContrastStrategy.FIRST_MARKER_SPLIT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preContrastWeight");
        assertThatThrownBy(() -> new ContrastPolicy(
                ContrastPolicy.ContrastStrategy.FIRST_MARKER_SPLIT, new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preContrastWeight");
        assertThatThrownBy(() -> new ContrastPolicy(
                ContrastPolicy.ContrastStrategy.FIRST_MARKER_SPLIT, new BigDecimal("1.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preContrastWeight");
        // Boundaries 0 and 1 are both legal explicit discounts.
        assertThat(new ContrastPolicy(
                ContrastPolicy.ContrastStrategy.FIRST_MARKER_SPLIT, BigDecimal.ZERO)
                .preContrastWeight()).isEqualByComparingTo("0");
        assertThat(new ContrastPolicy(
                ContrastPolicy.ContrastStrategy.FIRST_MARKER_SPLIT, BigDecimal.ONE)
                .preContrastWeight()).isEqualByComparingTo("1");
    }

    @Test
    void contrastMarkersAreDeeplyImmutableAndDefaultToEmpty() {
        Map<String, BigDecimal> scores = Map.of("gain", new BigDecimal("40"));
        java.util.Set<String> markers = new java.util.HashSet<>(Set.of("but", "however"));
        DictionarySnapshot snapshot = new DictionarySnapshot(
                "dict-1", scores, Set.of(), Map.of(), false, markers);

        markers.add("though");
        assertThat(snapshot.contrastMarkers()).containsExactlyInAnyOrder("but", "however");
        assertThatThrownBy(() -> snapshot.contrastMarkers().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
        // The backward-compatible constructor carries an explicitly empty marker set.
        assertThat(new DictionarySnapshot("dict-2", scores, Set.of(), Map.of(), false)
                .contrastMarkers()).isEmpty();
    }

    @Test
    void sampleSignalsRejectNegativeAndBlankInputs() {
        assertThatThrownBy(() -> new SampleSignals(new BigDecimal("-0.1"), 1L, "OFFICIAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quality");
        assertThatThrownBy(() -> new SampleSignals(BigDecimal.ONE, -1L, "OFFICIAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interactionCount");
        assertThatThrownBy(() -> new SampleSignals(BigDecimal.ONE, 1L, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceTier");
    }

    @Test
    void weightPolicyRequiresExplicitNonNegativeCoefficientsAndTiers() {
        Map<String, BigDecimal> tiers = Map.of("OFFICIAL", BigDecimal.ONE);
        assertThatThrownBy(() -> new WeightPolicy(
                new BigDecimal("-0.1"), BigDecimal.ONE, 100L, tiers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qualityFactor");
        assertThatThrownBy(() -> new WeightPolicy(
                BigDecimal.ONE, new BigDecimal("-0.1"), 100L, tiers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interactionFactor");
        assertThatThrownBy(() -> new WeightPolicy(
                BigDecimal.ONE, BigDecimal.ONE, 0L, tiers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interactionNorm");
        assertThatThrownBy(() -> new WeightPolicy(
                BigDecimal.ONE, BigDecimal.ONE, 100L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceWeights");
        assertThatThrownBy(() -> new WeightPolicy(
                BigDecimal.ONE, BigDecimal.ONE, 100L, Map.of("BAD", new BigDecimal("-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceWeights");
        // The frozen tier map is a defensive copy and is unmodifiable.
        java.util.Map<String, BigDecimal> mutable = new java.util.HashMap<>(tiers);
        WeightPolicy policy = new WeightPolicy(BigDecimal.ONE, BigDecimal.ONE, 100L, mutable);
        mutable.put("EXTRA", BigDecimal.TEN);
        assertThat(policy.sourceWeights()).containsOnlyKeys("OFFICIAL");
        assertThatThrownBy(() -> policy.sourceWeights().put("EXTRA", BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
