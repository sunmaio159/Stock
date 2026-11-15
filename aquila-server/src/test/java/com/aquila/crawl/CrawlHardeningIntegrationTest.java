package com.aquila.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.masterdata.MasterDataFixture;
import com.aquila.sourcepolicy.SourcePolicy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stage C Task 1 independent hardening pass (owner decision 2026-11-15, A1): I6 / I7 / I8.
 *
 * <p>Red baseline (pre-fix): I6 returned an unbounded {@code List<CoverageFactDto>} without a
 * {@code runId}, so there was no page bound and the client keyed rows by {@code pageIndex} alone;
 * I7's {@code runCollection} loop was {@code while (true)} with no upper bound, so a never-
 * terminating adapter would crawl forever; I8 mixed {@code Instant.now()} / {@code NOW(6)} with
 * no injected clock, so a timezone offset between the JVM and the DB was invisible to tests.
 * These tests pin the fixed behaviour; the red pre-fix state is that the bound / pagination /
 * clock-injection did not exist (the contract or method was absent), so the assertions below
 * could not hold.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CrawlHardeningIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T00:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private SourcePolicy policy;
    @Autowired private CoverageService coverage;
    @Autowired private ProcessingJobWorker worker;
    @Autowired private CrawlApplicationService crawl;
    @Autowired private SourceReadService read;
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
    }

    private int coverageCount(String runId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM m3_coverage_fact WHERE run_id = ?", Integer.class, runId);
    }

    /**
     * I6: the coverage listing must be paginated and every row must carry a stable, unique
     * run-scoped key (runId + pageIndex), not pageIndex alone — a repeated run that re-covers the
     * same source must not duplicate or mask keys.
     */
    @Test
    void coverageListingIsPaginatedWithStableRunScopedKey() {
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(3, 2, CrawlFixture.ADAPTER_CONTRACT));
        crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);
        // Second run over a different business interval => a distinct run_id.
        crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", END, END.plus(Duration.ofDays(1)));

        CoverageFactPage page0 = read.coverageFacts(CrawlFixture.SOURCE_CODE, 0, 2);
        assertThat(page0.total()).isEqualTo(6);
        assertThat(page0.page()).isEqualTo(0);
        assertThat(page0.size()).isEqualTo(2);
        assertThat(page0.items()).hasSize(2);

        Set<String> keys = new HashSet<>();
        Set<String> runIds = new HashSet<>();
        for (int p = 0; p < 3; p++) {
            CoverageFactPage page = read.coverageFacts(CrawlFixture.SOURCE_CODE, p, 2);
            for (CoverageFactDto fact : page.items()) {
                assertThat(fact.runId()).isNotNull();
                keys.add(fact.runId() + ":" + fact.pageIndex());
                runIds.add(fact.runId());
            }
        }
        assertThat(keys).hasSize(6);
        assertThat(runIds).hasSize(2);
    }

    /**
     * I7: a source adapter that never terminates must not crawl forever. With a small page bound
     * the run aborts as PARTIAL after at most that many pages. {@link Timeout} is a safety net:
     * pre-fix, with no bound, this test would never complete.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void crawlAbortsWhenAdapterNeverTerminates() {
        SourceAdapter cycling = request -> new SourcePage(
                request.sourceCode(), request.pageIndex(),
                List.of(new RawRecord(
                        request.sourceCode() + "-r" + request.pageIndex(), "payload")),
                "cursor-" + (request.pageIndex() + 1), false, false, true,
                CrawlFixture.ADAPTER_CONTRACT, 200, "application/json", 32L, null);
        CrawlApplicationService bounded =
                new CrawlApplicationService(jdbc, transactions, policy, cycling, coverage, worker);
        bounded.setMaxPages(5);

        CrawlResult result = bounded.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);
        assertThat(result.coverage().overallStatus()).isEqualTo("PARTIAL");
        assertThat(coverageCount(result.runId())).isLessThanOrEqualTo(5);
    }

    /**
     * I8: business timestamps must follow an injected clock, not the system wall clock or the DB
     * server's {@code NOW(6)}. With a clock fixed at an off-UTC offset, the stored created_at and
     * observed_at must equal the injected instant rather than the server-local now.
     */
    @Test
    void businessTimestampsFollowInjectedClock() {
        Clock fixed = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.ofHours(8));
        crawl.setClock(fixed);
        coverage.setClock(fixed);

        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(2, 1, CrawlFixture.ADAPTER_CONTRACT));
        CrawlResult result = crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);
        Instant expected = fixed.instant();

        Timestamp runCreated = jdbc.queryForObject(
                "SELECT created_at FROM m3_task_instance WHERE run_id = ?",
                Timestamp.class, result.runId());
        assertThat(runCreated.toInstant()).isEqualTo(expected);

        Timestamp observed = jdbc.queryForObject(
                "SELECT observed_at FROM m3_coverage_fact WHERE run_id = ? LIMIT 1",
                Timestamp.class, result.runId());
        assertThat(observed.toInstant()).isEqualTo(expected);
    }
}
