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
 *
 * <p>Stage D review 2026-11-15 (C-1/I-1): the title/body weights are explicit inputs, so
 * different weights must produce different scores; CJK terms must match continuous Chinese
 * text (the old ASCII word boundary made "利好" inside "公司利好公告" unreachable), while
 * pure ASCII terms must still respect word boundaries.
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

    private static DictionarySnapshot chineseDictionary() {
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("利好", new BigDecimal("40"));
        scores.put("重大利好", new BigDecimal("60"));
        scores.put("decline", new BigDecimal("-40"));
        return new DictionarySnapshot(
                "dict-zh", scores, Set.of(), Map.of(), false);
    }

    private static AlgorithmSnapshot algorithm() {
        return new AlgorithmSnapshot(
                "algo-1", MIN_CONFIDENCE, 2, 20, true,
                BigDecimal.ONE, BigDecimal.ONE, ConfidenceStrategy.HITS_OVER_FULL_HITS);
    }

    private static AlgorithmSnapshot weightedAlgorithm(
            String titleWeight, String bodyWeight) {
        return new AlgorithmSnapshot(
                "algo-weighted", MIN_CONFIDENCE, 2, 20, true,
                new BigDecimal(titleWeight), new BigDecimal(bodyWeight),
                ConfidenceStrategy.HITS_OVER_FULL_HITS);
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
                "algo-strict", new BigDecimal("0.90"), 100, 20, true,
                BigDecimal.ONE, BigDecimal.ONE, ConfidenceStrategy.HITS_OVER_FULL_HITS);

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

    /**
     * Review I-1: the old {@code Character.isLetterOrDigit} boundary treats every Chinese
     * character as a letter, so a CJK term could never match inside continuous Chinese
     * text — the realistic input shape for this platform. This is red against the old code.
     */
    @Test
    void continuousChineseTextMatchesWithoutSpaces() {
        SentimentResult result = engine.evaluate(
                input("公司利好公告", "SH600000"), chineseDictionary(), algorithm());

        assertThat(result.score()).isNotNull();
        assertThat(result.score()).isEqualByComparingTo("40.00");
        assertThat(result.hitWords()).containsExactly("利好");
    }

    /** Review I-1: pure ASCII terms must still not match inside a longer ASCII word. */
    @Test
    void asciiTermsStillRespectWordBoundaries() {
        SentimentResult substring = engine.evaluate(
                input("regainer", "SH600000"), dictionary("dict-1", "40", "-40"), algorithm());
        SentimentResult plural = engine.evaluate(
                input("gains", "SH600000"), dictionary("dict-1", "40", "-40"), algorithm());

        assertThat(substring.unknownReason()).isEqualTo("NO_HIT");
        assertThat(substring.score()).isNull();
        assertThat(plural.unknownReason()).isEqualTo("NO_HIT");
        assertThat(plural.score()).isNull();
    }

    /** CJK and ASCII mix both ways: a CJK term matches next to ASCII, and vice versa. */
    @Test
    void cjkAndAsciiTermsMatchAcrossScriptBoundaries() {
        SentimentResult cjkNextToAscii = engine.evaluate(
                input("bullish利好", "SH600000"), chineseDictionary(), algorithm());
        SentimentResult asciiNextToCjk = engine.evaluate(
                input("decline公司", "SH600000"), chineseDictionary(), algorithm());

        assertThat(cjkNextToAscii.score()).isEqualByComparingTo("40.00");
        assertThat(asciiNextToCjk.score()).isEqualByComparingTo("-40.00");
    }

    /** Longest-match-first must keep winning now that CJK terms are reachable at all. */
    @Test
    void longestChineseTermWinsOverItsSubterms() {
        SentimentResult result = engine.evaluate(
                input("重大利好", "SH600000"), chineseDictionary(), algorithm());

        assertThat(result.hitWords()).containsExactly("重大利好");
        assertThat(result.score()).isEqualByComparingTo("60.00");
    }

    /**
     * Review C-1: the title/body weighting is an explicit versioned input. With the old
     * hardcoded 1:1 concatenation both scores below are equal, so this is red there.
     */
    @Test
    void explicitSectionWeightsChangeTheScore() {
        AlgorithmSnapshot titleHeavy = weightedAlgorithm("2", "1");

        SentimentResult inTitle = engine.evaluate(
                new SentimentInput("rev-1", "gain", "", List.of("SH600000")),
                dictionary("dict-1", "40", "-40"), titleHeavy);
        SentimentResult inBody = engine.evaluate(
                new SentimentInput("rev-1", "", "gain", List.of("SH600000")),
                dictionary("dict-1", "40", "-40"), titleHeavy);

        assertThat(inTitle.score()).isEqualByComparingTo("80.00");
        assertThat(inBody.score()).isEqualByComparingTo("40.00");
    }

    /**
     * Review 2026-11-16 M-3 / round-4 M-2: the evidence list is bounded by the versioned
     * maxEvidenceHits (20 in the fixture) while the score keeps accumulating over the whole
     * text. A one-point word score keeps 25 hits below the +/-100 clamp, so an implementation
     * that also stopped scoring at the cap would produce 20.00 and fail this assertion.
     */
    @Test
    void evidenceCollectionStopsAtTheVersionedCap() {
        String manyHits = "gain ".repeat(25);
        SentimentResult result = engine.evaluate(
                input(manyHits, "SH600000"),
                dictionary("dict-1", "1", "-1"), algorithm());

        assertThat(result.hitWords()).hasSize(20);
        assertThat(result.score()).isEqualByComparingTo("25.00");
    }

    // ---- PO decision #4: contrast (concessive) rule -------------------------------

    private static AlgorithmSnapshot contrastAlgorithm(String preContrastWeight) {
        return new AlgorithmSnapshot(
                "algo-contrast", MIN_CONFIDENCE, 2, 20, true,
                BigDecimal.ONE, BigDecimal.ONE, ConfidenceStrategy.HITS_OVER_FULL_HITS,
                new ContrastPolicy(ContrastPolicy.ContrastStrategy.FIRST_MARKER_SPLIT,
                        new BigDecimal(preContrastWeight)));
    }

    private static DictionarySnapshot contrastDictionary(
            Map<String, BigDecimal> scores, Set<String> negations,
            Map<String, BigDecimal> degrees, Set<String> markers) {
        return new DictionarySnapshot("dict-contrast", scores, negations, degrees, false, markers);
    }

    /**
     * The first marker splits the section: text before it is discounted, text after it
     * keeps full weight. A no-op/global-discount implementation fails this exact value.
     */
    @Test
    void contrastDiscountsOnlyTextBeforeTheFirstMarker() {
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("gain", new BigDecimal("40"));
        scores.put("decline", new BigDecimal("-40"));

        SentimentResult result = engine.evaluate(
                input("gain but decline", "SH600000"),
                contrastDictionary(scores, Set.of(), Map.of(), Set.of("but")),
                contrastAlgorithm("0.5"));

        // +40*0.5 before the marker, -40*1 after => -20.00
        assertThat(result.score()).isEqualByComparingTo("-20.00");
        assertThat(result.hitWords()).containsExactly("gain", "decline");
    }

    /** Degree state pending before a marker is reset, not carried into the concessive clause. */
    @Test
    void contrastDoesNotCarryDegreeStateAcrossTheSplit() {
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("gain", new BigDecimal("40"));

        SentimentResult result = engine.evaluate(
                input("gain sharply but gain", "SH600000"),
                contrastDictionary(scores, Set.of(), Map.of("sharply", new BigDecimal("1.5")),
                        Set.of("but")),
                contrastAlgorithm("0.5"));

        // first gain +40*0.5=+20; "sharply" pending is dropped at the marker; second +40
        assertThat(result.score()).isEqualByComparingTo("60.00");
    }

    /** Negation pending before a marker is reset, not carried across the split. */
    @Test
    void contrastDoesNotCarryNegationAcrossTheSplit() {
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("gain", new BigDecimal("40"));

        SentimentResult result = engine.evaluate(
                input("gain not but gain", "SH600000"),
                contrastDictionary(scores, Set.of("not"), Map.of(), Set.of("but")),
                contrastAlgorithm("0.5"));

        // first gain +20; "not" pending is dropped at the marker; second gain +40
        assertThat(result.score()).isEqualByComparingTo("60.00");
    }

    /** A disabled contrast policy (null) is byte-for-byte the legacy behaviour. */
    @Test
    void disabledContrastKeepsTheLegacyScore() {
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("gain", new BigDecimal("40"));
        scores.put("decline", new BigDecimal("-40"));
        DictionarySnapshot withMarkers = contrastDictionary(
                scores, Set.of(), Map.of(), Set.of("but"));
        DictionarySnapshot withoutMarkers = new DictionarySnapshot(
                "dict-contrast", scores, Set.of(), Map.of(), false);

        SentimentResult marked = engine.evaluate(
                input("gain but decline", "SH600000"), withMarkers, algorithm());
        SentimentResult plain = engine.evaluate(
                input("gain but decline", "SH600000"), withoutMarkers, algorithm());

        assertThat(marked.score()).isEqualByComparingTo("0.00");
        assertThat(plain.score()).isEqualByComparingTo(marked.score());
    }
}
