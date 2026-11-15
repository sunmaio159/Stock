package com.aquila.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Falsifying test for round-1 falsifying case 4, which round-2 finding F-5 recorded as
 * never written: a status outside the known vocabulary must not reach the positive branch.
 *
 * <p>Until now the only thing preventing an unknown literal was the database CHECK
 * constraint, so the code path could not be exercised at all — the guard was the schema,
 * not the code. That is exactly the weakness I1 was raised for, so the classification is
 * now a pure function over page verdicts and is tested directly, without a database.
 */
class CoverageVerdictClassificationTest {

    @Test
    void unrecognisedStatusCannotProduceCompleteEvenWhenTheChainLooksProved() {
        List<CoverageService.PageVerdict> pages = List.of(
                new CoverageService.PageVerdict("COMPLETE", null, "cursor-1", "adapter-1.0", false),
                new CoverageService.PageVerdict("SOMETHING_NEW", "cursor-1", null, "terminal-1", true));

        assertThat(CoverageService.resolveOverall(pages)).isEqualTo("UNKNOWN");
    }

    @Test
    void provedChainOfKnownStatusesIsComplete() {
        List<CoverageService.PageVerdict> pages = List.of(
                new CoverageService.PageVerdict("COMPLETE", null, "cursor-1", "adapter-1.0", false),
                new CoverageService.PageVerdict("COMPLETE", "cursor-1", null, "terminal-1", true));

        assertThat(CoverageService.resolveOverall(pages)).isEqualTo("COMPLETE");
    }

    /**
     * Third-round finding G-1, which also closes round-1 falsifying case 3 that had never
     * been written. A page that the adapter did <em>not</em> report as terminal still gets
     * the page-level status COMPLETE, so an empty {@code cursor_after} cannot be used to
     * infer that the interval ended: an untraceable recent-N source whose last persisted
     * page was non-terminal would otherwise be declared COMPLETE. The verdict is written
     * first and observed red, because the proof currently consults no terminal flag at all.
     */
    @Test
    void nonTerminalPageWithoutACursorCannotProveCompleteness() {
        List<CoverageService.PageVerdict> singleNonTerminalPage = List.of(
                new CoverageService.PageVerdict("COMPLETE", null, null, "evidence-1", false));

        assertThat(CoverageService.resolveOverall(singleNonTerminalPage)).isNotEqualTo("COMPLETE");
    }

    /**
     * Same defect at the tail of a longer run: the closing page is non-terminal, so nothing
     * here proves the interval was finished even though the chain is unbroken from the
     * boundary and an evidence reference is present.
     */
    @Test
    void unbrokenChainEndingOnANonTerminalPageCannotProveCompleteness() {
        List<CoverageService.PageVerdict> pages = List.of(
                new CoverageService.PageVerdict("COMPLETE", null, "cursor-1", "adapter-1.0", false),
                new CoverageService.PageVerdict("COMPLETE", "cursor-1", null, "evidence-2", false));

        assertThat(CoverageService.resolveOverall(pages)).isNotEqualTo("COMPLETE");
    }
}
