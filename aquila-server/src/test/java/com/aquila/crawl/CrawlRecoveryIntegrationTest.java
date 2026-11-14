package com.aquila.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.masterdata.MasterDataFixture;
import java.time.Instant;
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
class CrawlRecoveryIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T00:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CrawlApplicationService crawl;
    @Autowired private ProcessingJobWorker worker;
    @Autowired private FixtureSourceAdapter adapter;

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
        adapter.clearPlans();
        crawl.setPageCommitHook(PageCommitHook.NOOP);
    }

    private int rawCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM m3_raw_record", Integer.class);
    }

    private int jobCount(String state) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM m3_processing_job WHERE state = ?", Integer.class, state);
    }

    private long cursorVersion() {
        return jdbc.queryForObject(
                "SELECT cursor_version FROM m3_source_config WHERE source_code = ?",
                Long.class, CrawlFixture.SOURCE_CODE);
    }

    @Test
    void completeRunPersistsRawAndLeavesDurableJobs() {
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(2, 3, CrawlFixture.ADAPTER_CONTRACT));

        CrawlResult result = crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);

        assertThat(result.coverage().overallStatus()).isEqualTo("COMPLETE");
        assertThat(rawCount()).isEqualTo(6);
        assertThat(jobCount("PENDING")).isEqualTo(6);
        assertThat(worker.recover()).isEqualTo(6);
        assertThat(jobCount("COMPLETE")).isEqualTo(6);
    }

    @Test
    void pageTwoFailureKeepsRunPartialAndCursorAdvancesOnlyOnePage() {
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                new FixtureSourcePlan(2, 3, 1, -1, true, CrawlFixture.ADAPTER_CONTRACT));

        CrawlResult result = crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);

        assertThat(result.coverage().overallStatus()).isEqualTo("PARTIAL");
        assertThat(rawCount()).isEqualTo(3);
        assertThat(cursorVersion()).isEqualTo(1);
    }

    @Test
    void transactionRollbackPreservesCursorAndRaw() {
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(1, 3, CrawlFixture.ADAPTER_CONTRACT));
        crawl.setPageCommitHook(pageIndex -> {
            if (pageIndex == 0) {
                throw new IllegalStateException("Injected durable-store failure");
            }
        });

        assertThatThrownBy(() -> crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rawCount()).isZero();
        assertThat(cursorVersion()).isZero();
    }

    @Test
    void missedQueueNotificationIsRecoveredByWorker() {
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(1, 3, CrawlFixture.ADAPTER_CONTRACT));

        crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);
        assertThat(jobCount("PENDING")).isEqualTo(3);

        assertThat(worker.recover()).isEqualTo(3);
        assertThat(jobCount("COMPLETE")).isEqualTo(3);
    }

    @Test
    void repeatedRunWithSameIntervalIsIdempotent() {
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(1, 3, CrawlFixture.ADAPTER_CONTRACT));

        CrawlResult first = crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);
        CrawlResult second = crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);

        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(rawCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM m3_coverage_fact WHERE run_id = ?",
                Integer.class, first.runId())).isEqualTo(1);
    }

    @Test
    void untraceableSourceNeverCompletesOnCrawl() {
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                new FixtureSourcePlan(1, 3, -1, -1, false, CrawlFixture.ADAPTER_CONTRACT));

        CrawlResult result = crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);

        assertThat(result.coverage().overallStatus()).isEqualTo("UNKNOWN");
    }
}
