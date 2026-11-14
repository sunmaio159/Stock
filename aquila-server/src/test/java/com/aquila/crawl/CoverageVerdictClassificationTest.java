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
                new CoverageService.PageVerdict("COMPLETE", null, "cursor-1", "adapter-1.0"),
                new CoverageService.PageVerdict("SOMETHING_NEW", "cursor-1", null, "terminal-1"));

        assertThat(CoverageService.resolveOverall(pages)).isEqualTo("UNKNOWN");
    }

    @Test
    void provedChainOfKnownStatusesIsComplete() {
        List<CoverageService.PageVerdict> pages = List.of(
                new CoverageService.PageVerdict("COMPLETE", null, "cursor-1", "adapter-1.0"),
                new CoverageService.PageVerdict("COMPLETE", "cursor-1", null, "terminal-1"));

        assertThat(CoverageService.resolveOverall(pages)).isEqualTo("COMPLETE");
    }
}
