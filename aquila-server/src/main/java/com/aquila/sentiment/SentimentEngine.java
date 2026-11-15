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
 * threshold: every term score, negation, degree multiplier, confidence parameter,
 * section weight and eligibility switch arrives through the explicit versioned snapshots
 * (Stage D review C-1: the former implicit 1:1 title/body weighting and the implicit
 * linear confidence reading are now explicit inputs; no PO-owned default exists here).
 *
 * <p>Unknown is NULL for both score and confidence, never a neutral zero. Score and
 * confidence are clamped to the frozen [-100,100] / [0,1] ranges.
 *
 * <p>Title and body are scanned separately, each with its own explicit weight; a negation
 * or degree adverb never carries from the title into the body. Matching is
 * longest-match-first, then lexicographic, over a sorted set: deterministic for
 * identical versioned inputs. The word-boundary rule applies to pure ASCII terms only
 * (Stage D review I-1): CJK text has no space-separated words, so a Chinese term must
 * match inside continuous Chinese text, while "gain" must still not match inside
 * "regain".
 */
public final class SentimentEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MINUS_HUNDRED = new BigDecimal("-100");
    private static final String OPPOSITE_STANCE_REASON =
            "Opposite-stance content mentions multiple securities";

    public SentimentResult evaluate(
            SentimentInput input, DictionarySnapshot dictionary, AlgorithmSnapshot algorithm) {
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

        Accumulator acc = new Accumulator(algorithm.maxEvidenceHits());
        scan(input.title().toLowerCase(Locale.ROOT), algorithm.titleWeight(),
                scores, negations, degrees, ordered, acc);
        scan(input.body().toLowerCase(Locale.ROOT), algorithm.bodyWeight(),
                scores, negations, degrees, ordered, acc);

        boolean oppositeStance = acc.hasPositive && acc.hasNegative;
        boolean ambiguous = algorithm.excludeOppositeStanceMultiSecurity()
                && oppositeStance
                && input.securityCodes().size() > 1;
        Eligibility eligibility = ambiguous
                ? Eligibility.AMBIGUOUS_MULTI_SECURITY
                : Eligibility.ELIGIBLE;
        String exclusionReason = ambiguous ? OPPOSITE_STANCE_REASON : null;

        if (acc.scoreHits == 0) {
            return unknown(input, dictionary, algorithm, "NO_HIT", acc.hits, oppositeStance,
                    eligibility, exclusionReason);
        }
        if (algorithm.confidenceStrategy() != ConfidenceStrategy.HITS_OVER_FULL_HITS) {
            throw new IllegalArgumentException(
                    "Unsupported confidence strategy: " + algorithm.confidenceStrategy());
        }
        BigDecimal confidence = BigDecimal.valueOf(acc.scoreHits)
                .divide(BigDecimal.valueOf(algorithm.fullConfidenceHits()), 4, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE);
        if (confidence.compareTo(algorithm.minConfidence()) < 0) {
            return unknown(input, dictionary, algorithm, "LOW_CONFIDENCE", acc.hits, oppositeStance,
                    eligibility, exclusionReason);
        }
        BigDecimal score = acc.total.max(MINUS_HUNDRED).min(HUNDRED).setScale(2, RoundingMode.HALF_UP);
        return new SentimentResult(
                input.docRevisionId(), algorithm.algoVersion(), dictionary.dictVersion(),
                score, confidence, null, eligibility, exclusionReason, acc.hits, oppositeStance);
    }

    /** One section (title or body) of the document, weighted by its explicit versioned weight. */
    private void scan(
            String text,
            BigDecimal sectionWeight,
            Map<String, BigDecimal> scores,
            Set<String> negations,
            Map<String, BigDecimal> degrees,
            List<String> ordered,
            Accumulator acc) {
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
                if (isPureAsciiAlnum(term) && !wordBoundary(text, index, term.length())) {
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
                        .multiply(pendingDegree)
                        .multiply(sectionWeight);
                total = total.add(adjusted);
                scoreHits++;
                hasPositive |= adjusted.signum() > 0;
                hasNegative |= adjusted.signum() < 0;
                negateNext = false;
                pendingDegree = BigDecimal.ONE;
                hits.add(matched);
            }
        }
        acc.absorb(total, scoreHits, hasPositive, hasNegative, hits);
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

    /**
     * A term that is entirely ASCII letters/digits is a space-delimited word, so a match
     * must not sit inside a longer ASCII word. A term containing any non-ASCII character
     * is CJK-class: CJK languages do not separate words with spaces, so any adjacency is
     * a legitimate match position (Stage D review I-1).
     */
    private static boolean isPureAsciiAlnum(String term) {
        for (int index = 0; index < term.length(); index++) {
            if (!isAsciiAlnum(term.charAt(index))) {
                return false;
            }
        }
        return !term.isEmpty();
    }

    private static boolean wordBoundary(String text, int start, int length) {
        char before = start == 0 ? ' ' : text.charAt(start - 1);
        int end = start + length;
        char after = end >= text.length() ? ' ' : text.charAt(end);
        return !isAsciiAlnum(before) && !isAsciiAlnum(after);
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** Per-section state merged across title and body; evidence is capped at the explicit bound. */
    private static final class Accumulator {
        private BigDecimal total = BigDecimal.ZERO;
        private int scoreHits;
        private boolean hasPositive;
        private boolean hasNegative;
        private final List<String> hits = new ArrayList<>();
        private final int maxEvidenceHits;

        Accumulator(int maxEvidenceHits) {
            this.maxEvidenceHits = maxEvidenceHits;
        }

        void absorb(BigDecimal sectionTotal, int sectionHits, boolean sectionPositive,
                boolean sectionNegative, List<String> sectionHitsList) {
            total = total.add(sectionTotal);
            scoreHits += sectionHits;
            hasPositive |= sectionPositive;
            hasNegative |= sectionNegative;
            for (String hit : sectionHitsList) {
                if (hits.size() < maxEvidenceHits) {
                    hits.add(hit);
                }
            }
        }
    }
}
