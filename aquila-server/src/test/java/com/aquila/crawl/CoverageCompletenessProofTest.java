package com.aquila.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.masterdata.MasterDataFixture;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Falsifying tests for finding I1: an overall COMPLETE verdict must be proved, not merely
 * the absence of a worse status. A run is COMPLETE only when every page succeeded, the run
 * reached a terminal page carrying non-null terminal evidence, and the page cursors form an
 * unbroken chain.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CoverageCompletenessProofTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T00:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CoverageService coverage;
    @Autowired private FixtureSourceAdapter fixtureAdapter;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MasterDataFixture.register(registry);
    }

    @BeforeEach
    void reset() {
        CrawlFixture.reset(jdbc);
    }

    @AfterEach
    void cleanup() {
        CrawlFixture.clear(jdbc);
        fixtureAdapter.clearPlans();
    }

    @Test
    void successfulPagesWithoutATerminalPageAreNotComplete() {
        String runId = newRun();
        coverage.commitPage(page(runId, 0, null, "cursor-1", false, true, "adapter-1.0"));

        assertThat(coverage.computeOverall(runId).overallStatus()).isNotEqualTo("COMPLETE");
    }

    @Test
    void brokenCursorChainIsNotCompleteEvenWithTerminalEvidence() {
        String runId = newRun();
        coverage.commitPage(page(runId, 0, null, "cursor-1", false, true, "adapter-1.0"));
        // Page 2 does not continue from cursor-1, so the interval is not provably covered.
        coverage.commitPage(page(runId, 1, "cursor-9", null, true, true, "terminal-1"));

        assertThat(coverage.computeOverall(runId).overallStatus()).isNotEqualTo("COMPLETE");
    }

    @Test
    void continuousRunEndingWithTerminalEvidenceIsComplete() {
        String runId = newRun();
        coverage.commitPage(page(runId, 0, null, "cursor-1", false, true, "adapter-1.0"));
        coverage.commitPage(page(runId, 1, "cursor-1", null, true, true, "terminal-1"));

        assertThat(coverage.computeOverall(runId).overallStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void terminalFactIsOnlyProofWhenItIsTheLastPageOfTheRun() {
        String runId = newRun();
        // A terminal fact on page 0 proves nothing while the run continues past it.
        coverage.commitPage(page(runId, 0, null, null, true, true, "terminal-1"));
        coverage.commitPage(page(runId, 1, null, "cursor-2", false, true, "adapter-1.0"));

        assertThat(coverage.computeOverall(runId).overallStatus()).isNotEqualTo("COMPLETE");
    }

    @Test
    void runMustBeginAtTheIntervalBoundaryToProveCompleteness() {
        String runId = newRun();
        // The chain is internally continuous but starts mid-stream, so the interval before
        // "cursor-3" was never covered by this run.
        coverage.commitPage(page(runId, 0, "cursor-3", "cursor-4", false, true, "adapter-1.0"));
        coverage.commitPage(page(runId, 1, "cursor-4", null, true, true, "terminal-1"));

        assertThat(coverage.computeOverall(runId).overallStatus()).isNotEqualTo("COMPLETE");
    }

    private String newRun() {
        return UUID.randomUUID().toString();
    }

    private PageCommit page(
            String runId, int pageIndex, String cursorBefore, String cursorAfter,
            boolean terminal, boolean traceable, String evidenceReference) {
        return new PageCommit(
                runId, CrawlFixture.SOURCE_CODE, "DEFAULT", 1, START, END,
                cursorBefore, cursorAfter, pageIndex, PageOutcome.SUCCESS,
                terminal, false, traceable, CrawlFixture.ADAPTER_CONTRACT,
                evidenceReference, null);
    }
}
