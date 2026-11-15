package com.aquila.sentiment;

import com.aquila.sentiment.SentimentModel.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic document-level rule engine. It contains no business dictionary and no
 * threshold: every term score, negation, degree multiplier, confidence parameter and
 * eligibility switch arrives through the explicit versioned snapshots. Title/body weighting
 * is deliberately equal until the PO freezes calibrated weights (unified plan section 9.1),
 * and the uncalibrated result is labelled EXPERIMENTAL at the aggregation layer.
 *
 * <p>Unknown is NULL for both score and confidence, never a neutral zero. Score and
 * confidence are clamped to the frozen [-100,100] / [0,1] ranges.
 */
public final class SentimentEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MINUS_HUNDRED = new BigDecimal("-100");
    private static final String OPPOSITE_STANCE_REASON =
            "Opposite-stance content mentions multiple securities";

    public SentimentResult evaluate(
            SentimentInput input, DictionarySnapshot dictionary, AlgorithmSnapshot algorithm) {
        String text = (input.title() + " " + input.body()).toLowerCase(Locale.ROOT);
        Map<String, BigDecimal> scores = lowerKeys(dictionary.wordScores());
        Map<String, BigDecimal> degrees = lowerKeys(dictionary.degreeMultipliers());
        Set<String> negations = new LinkedHashSet<>();
        dictionary.negations().forEach(word -> negations.add(word.toLowerCase(Locale.ROOT)));

        // Longest-match-first, then lexicographic, over a sorted set: deterministic for
        // identical versioned inputs.
        Set<String> terms = new LinkedHashSet<>(scores.keySet());
        terms.addAll(negations);
        terms.addAll(degrees.keySet());
        List<String> ordered = terms.stream()
                .sorted(Comparator.comparingInt(String::length).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        BigDecimal total = BigDecimal.ZERO;
        int scoreHits = 0;
        boolean hasPositive = false;
        boolean hasNegative = false;
        boolean negateNext = false;
        BigDecimal pendingDegree = BigDecimal.ONE;
        List<String> hits = new ArrayList<>();

        int index = 0;
        while (index < text.length()) {
            String matched = null;
            for (String term : ordered) {
                if (term.isEmpty() || !text.startsWith(term, index)) {
                    continue;
                }
                if (!wordBoundary(text, index, term.length())) {
                    continue;
                }
                matched = term;
                break;
            }
            if (matched == null) {
                index++;
                continue;
            }
            index += matched.length();
            if (negations.contains(matched)) {
                negateNext = true;
            } else if (degrees.containsKey(matched)) {
                pendingDegree = degrees.get(matched);
            } else {
                BigDecimal adjusted = scores.get(matched)
                        .multiply(negateNext ? BigDecimal.valueOf(-1) : BigDecimal.ONE)
                        .multiply(pendingDegree);
                total = total.add(adjusted);
                scoreHits++;
                hasPositive |= adjusted.signum() > 0;
                hasNegative |= adjusted.signum() < 0;
                negateNext = false;
                pendingDegree = BigDecimal.ONE;
                if (hits.size() < algorithm.maxEvidenceHits()) {
                    hits.add(matched);
                }
            }
        }

        boolean oppositeStance = hasPositive && hasNegative;
        boolean ambiguous = algorithm.excludeOppositeStanceMultiSecurity()
                && oppositeStance
                && input.securityCodes().size() > 1;
        Eligibility eligibility = ambiguous
                ? Eligibility.AMBIGUOUS_MULTI_SECURITY
                : Eligibility.ELIGIBLE;
        String exclusionReason = ambiguous ? OPPOSITE_STANCE_REASON : null;

        if (scoreHits == 0) {
            return unknown(input, dictionary, algorithm, "NO_HIT", hits, oppositeStance,
                    eligibility, exclusionReason);
        }
        BigDecimal confidence = BigDecimal.valueOf(scoreHits)
                .divide(BigDecimal.valueOf(algorithm.fullConfidenceHits()), 4, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE);
        if (confidence.compareTo(algorithm.minConfidence()) < 0) {
            return unknown(input, dictionary, algorithm, "LOW_CONFIDENCE", hits, oppositeStance,
                    eligibility, exclusionReason);
        }
        BigDecimal score = total.max(MINUS_HUNDRED).min(HUNDRED).setScale(2, RoundingMode.HALF_UP);
        return new SentimentResult(
                input.docRevisionId(), algorithm.algoVersion(), dictionary.dictVersion(),
                score, confidence, null, eligibility, exclusionReason, hits, oppositeStance);
    }

    private static SentimentResult unknown(
            SentimentInput input,
            DictionarySnapshot dictionary,
            AlgorithmSnapshot algorithm,
            String reason,
            List<String> hits,
            boolean oppositeStance,
            Eligibility eligibility,
            String exclusionReason) {
        return new SentimentResult(
                input.docRevisionId(), algorithm.algoVersion(), dictionary.dictVersion(),
                null, null, reason, eligibility, exclusionReason, hits, oppositeStance);
    }

    private static Map<String, BigDecimal> lowerKeys(Map<String, BigDecimal> source) {
        Map<String, BigDecimal> result = new TreeMap<>();
        source.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    private static boolean wordBoundary(String text, int start, int length) {
        char before = start == 0 ? ' ' : text.charAt(start - 1);
        int end = start + length;
        char after = end >= text.length() ? ' ' : text.charAt(end);
        return !Character.isLetterOrDigit(before) && !Character.isLetterOrDigit(after);
    }
}
