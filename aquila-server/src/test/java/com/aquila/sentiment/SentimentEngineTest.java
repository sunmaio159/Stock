package com.aquila.sentiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquila.sentiment.SentimentModel.*;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Falsifying tests for the Stage D Task 1 engine. Every numeric parameter is supplied by an
 * explicit versioned snapshot; no business dictionary or threshold is embedded in the tests
 * as a production default (unified plan section 9.1 freezes those through the PO).
 */
class SentimentEngineTest {

    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.10");

    private final SentimentEngine engine = new SentimentEngine();

    private static DictionarySnapshot dictionary(
            String version, String positiveScore, String negativeScore) {
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("gain", new BigDecimal(positiveScore));
        scores.put("decline", new BigDecimal(negativeScore));
        return new DictionarySnapshot(
                version, scores, Set.of("not"), Map.of("sharply", new BigDecimal("1.5")), false);
    }

    private static AlgorithmSnapshot algorithm() {
        return new AlgorithmSnapshot("algo-1", MIN_CONFIDENCE, 2, 20, true);
    }

    private static SentimentInput input(String text, String... securities) {
        return new SentimentInput("rev-1", "", text, List.of(securities));
    }

    @Test
    void unknownDocumentIsNullNotNeutralZero() {
        SentimentResult result = engine.evaluate(
                input("no matching terms here", "SH600000"),
                dictionary("dict-1", "40", "-40"),
                algorithm());

        assertThat(result.score()).isNull();
        assertThat(result.confidence()).isNull();
        assertThat(result.score()).isNotEqualTo(BigDecimal.ZERO);
        assertThat(result.unknownReason()).isNotBlank();
    }

    @Test
    void scoreAndConfidenceStayInsideFrozenBounds() {
        SentimentResult positive = engine.evaluate(
                input("gain gain gain gain gain", "SH600000"),
                dictionary("dict-1", "40", "-40"),
                algorithm());
        SentimentResult negative = engine.evaluate(
                input("decline decline decline decline decline", "SH600000"),
                dictionary("dict-1", "40", "-40"),
                algorithm());

        assertThat(positive.score()).isBetween(new BigDecimal("-100"), new BigDecimal("100"));
        assertThat(negative.score()).isBetween(new BigDecimal("-100"), new BigDecimal("100"));
        assertThat(positive.confidence()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(negative.confidence()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    }

    @Test
    void sameVersionedInputsProduceIdenticalResult() {
        DictionarySnapshot dict = dictionary("dict-1", "40", "-40");
        AlgorithmSnapshot algo = algorithm();
        SentimentInput observed = input("gain decline gain", "SH600000");

        assertThat(engine.evaluate(observed, dict, algo))
                .isEqualTo(engine.evaluate(observed, dict, algo));
    }

    @Test
    void dictionaryVersionIsCarriedAndNeverSilentlyReused() {
        SentimentResult first = engine.evaluate(
                input("gain", "SH600000"), dictionary("dict-1", "40", "-40"), algorithm());
        SentimentResult second = engine.evaluate(
                input("gain", "SH600000"), dictionary("dict-2", "10", "-10"), algorithm());

        assertThat(first.dictVersion()).isEqualTo("dict-1");
        assertThat(second.dictVersion()).isEqualTo("dict-2");
        assertThat(first.score()).isNotEqualTo(second.score());
    }

    @Test
    void lowConfidenceBelowExplicitThresholdRemainsUnknown() {
        AlgorithmSnapshot strict = new AlgorithmSnapshot(
                "algo-strict", new BigDecimal("0.90"), 100, 20, true);

        SentimentResult result = engine.evaluate(
                input("gain", "SH600000"), dictionary("dict-1", "40", "-40"), strict);

        assertThat(result.score()).isNull();
        assertThat(result.unknownReason()).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    void negationFlipsTheFollowingHit() {
        SentimentResult plain = engine.evaluate(
                input("gain", "SH600000"), dictionary("dict-1", "40", "-40"), algorithm());
        SentimentResult negated = engine.evaluate(
                input("not gain", "SH600000"), dictionary("dict-1", "40", "-40"), algorithm());

        assertThat(plain.score()).isPositive();
        assertThat(negated.score()).isNegative();
    }

    @Test
    void oppositeStanceMultiSecurityContentIsMarkedIneligible() {
        SentimentResult result = engine.evaluate(
                input("gain decline", "SH600000", "SZ000001"),
                dictionary("dict-1", "40", "-40"),
                algorithm());

        assertThat(result.oppositeStance()).isTrue();
        assertThat(result.eligibility()).isEqualTo(Eligibility.AMBIGUOUS_MULTI_SECURITY);
        assertThat(result.exclusionReason()).isNotBlank();
    }

    @Test
    void oppositeStanceSingleSecurityContentStaysEligible() {
        SentimentResult result = engine.evaluate(
                input("gain decline", "SH600000"),
                dictionary("dict-1", "40", "-40"),
                algorithm());

        assertThat(result.oppositeStance()).isTrue();
        assertThat(result.eligibility()).isEqualTo(Eligibility.ELIGIBLE);
    }
}
