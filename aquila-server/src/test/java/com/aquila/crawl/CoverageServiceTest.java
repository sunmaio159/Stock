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

@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CoverageServiceTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CoverageService coverage;

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
    }

    private CoverageState commitRun(boolean traceable, int totalPages, int failPage, int unrecoverablePage) {
        String runId = UUID.randomUUID().toString();
        CoverageState last = null;
        for (int page = 0; page < totalPages; page++) {
            boolean terminal = page == totalPages - 1;
            boolean fail = page == failPage;
            boolean unrecoverable = page == unrecoverablePage;
            last = coverage.commitPage(new PageCommit(
                    runId, CrawlFixture.SOURCE_CODE, "DEFAULT", 1,
                    Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-02T00:00:00Z"),
                    // The first page begins at the interval boundary, so it carries no cursor,
                    // exactly as the collection loop starts with a null cursor.
                    page == 0 ? null : "cursor-" + page,
                    terminal ? null : "cursor-" + (page + 1), page,
                    fail ? PageOutcome.FAILURE : PageOutcome.SUCCESS,
                    terminal, unrecoverable, traceable, CrawlFixture.ADAPTER_CONTRACT,
                    terminal ? "terminal-" + page : null, null));
        }
        return last;
    }

    @Test
    void completeRunIsDeclaredComplete() {
        CoverageState state = commitRun(true, 2, -1, -1);

        assertThat(state.overallStatus()).isEqualTo("COMPLETE");
        assertThat(state.pagesComplete()).isEqualTo(2);
        assertThat(state.pagesPartial()).isZero();
    }

    @Test
    void pageTwoFailureKeepsTheRunPartial() {
        CoverageState state = commitRun(true, 2, 1, -1);

        assertThat(state.overallStatus()).isEqualTo("PARTIAL");
        assertThat(state.pagesComplete()).isEqualTo(1);
        assertThat(state.pagesPartial()).isEqualTo(1);
    }

    @Test
    void untraceableRecentNSourceIsNeverComplete() {
        CoverageState state = commitRun(false, 1, -1, -1);

        assertThat(state.overallStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void unrecoverableGapStaysUnrecoverable() {
        CoverageState state = commitRun(true, 2, -1, 1);

        assertThat(state.overallStatus()).isEqualTo("UNRECOVERABLE");
    }
}
