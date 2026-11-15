package com.aquila.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.masterdata.MasterDataFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Stage C Task 1 follow-up (1.x) falsifying tests for I3 / I4 / I5 and the controller
 * default-interval fix (I3 sub-item b). These assert the post-fix behaviour; on the
 * pre-fix code they fail (I3 short-circuits to PARTIAL, I4 folds to PARTIAL / is blocked
 * by the state CHECK, I5 DDL allows NULL).
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CrawlResumptionIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T00:00:00Z");

    @Autowired private JdbcTemplate jdbc;
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

    private int rawCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM m3_raw_record", Integer.class);
    }

    private int coverageCount(String runId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM m3_coverage_fact WHERE run_id = ?", Integer.class, runId);
    }

    private String runState(String runId) {
        return jdbc.queryForObject(
                "SELECT state FROM m3_task_instance WHERE run_id = ?", String.class, runId);
    }

    /**
     * Round-1 falsifying case 6 (I3 sub-item a): a failed page must not strand the
     * interval in PARTIAL. Re-running the same business interval resumes from the last
     * committed page and completes the interval without duplicating raw rows.
     */
    @Test
    void resumeAfterFailedPageCompletesIntervalWithoutDuplicatingRaw() {
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                new FixtureSourcePlan(2, 3, 1, -1, true, CrawlFixture.ADAPTER_CONTRACT));

        CrawlResult first = crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);
        assertThat(first.coverage().overallStatus()).isEqualTo("PARTIAL");
        assertThat(rawCount()).isEqualTo(3);

        // Retry with a plan that no longer fails page 1, same business interval => resume.
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(2, 3, CrawlFixture.ADAPTER_CONTRACT));

        CrawlResult second = crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);
        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(second.coverage().overallStatus()).isEqualTo("COMPLETE");
        assertThat(rawCount()).isEqualTo(6);
        assertThat(coverageCount(first.runId())).isEqualTo(2);
    }

    /** I4: a terminal-but-untraceable run resolves to UNKNOWN; the run state must carry it. */
    @Test
    void untraceableTerminalRunStateIsUnknownNotPartial() {
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                new FixtureSourcePlan(1, 3, -1, -1, false, CrawlFixture.ADAPTER_CONTRACT));
        CrawlResult result = crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);
        assertThat(result.coverage().overallStatus()).isEqualTo("UNKNOWN");
        assertThat(runState(result.runId())).isEqualTo("UNKNOWN");
    }

    /** I4: a page flagged unrecoverable yields an UNRECOVERABLE run state, not PARTIAL. */
    @Test
    void unrecoverablePageRunStateIsUnrecoverableNotPartial() {
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                new FixtureSourcePlan(1, 3, -1, 0, true, CrawlFixture.ADAPTER_CONTRACT));
        CrawlResult result = crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);
        assertThat(result.coverage().overallStatus()).isEqualTo("UNRECOVERABLE");
        assertThat(runState(result.runId())).isEqualTo("UNRECOVERABLE");
    }

    /** I5: the durable-job idempotency component must be enforced by DDL. */
    @Test
    void processingJobInputRevisionIsNotNullable() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO m3_processing_job
                    (job_id, input_type, input_key, source_code, raw_record_id, fetched_date,
                     stage, processor_version, input_revision, state, fencing_token, created_at, updated_at)
                VALUES (?, 'RAW', ?, ?, ?, CURDATE(), 'PARSE', ?, NULL, 'PENDING', 0, NOW(6), NOW(6))
                """, UUID.randomUUID().toString(), "k", CrawlFixture.SOURCE_CODE,
                UUID.randomUUID().toString(), CrawlFixture.ADAPTER_CONTRACT))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** I5: the exact business interval must be enforced by DDL. */
    @Test
    void coverageBusinessIntervalIsNotNullable() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO m3_coverage_fact
                    (coverage_id, source_code, run_id, subscription_scope, subscription_version,
                     business_interval_start, business_interval_end, cursor_before, cursor_after,
                     coverage_status, reason, adapter_contract_version, evidence_reference,
                     observed_at, page_index, page_terminal, created_at)
                VALUES (?, ?, ?, 'DEFAULT', 1, NULL, NULL, NULL, NULL, 'PARTIAL', 'x',
                        ?, 'e', NOW(6), 0, FALSE, NOW(6))
                """, UUID.randomUUID().toString(), CrawlFixture.SOURCE_CODE, UUID.randomUUID().toString(),
                CrawlFixture.ADAPTER_CONTRACT))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * I3 sub-item b: the controller's default collection interval must be a deterministic,
     * source-derived window (the source's effective range), not {@code Instant.now()}, so
     * operator triggers over the same business interval share a run_key.
     */
    @Test
    void collectionIntervalIsSourceDerivedNotNow() {
        SourceReadService.SourceInterval first = read.collectionInterval(CrawlFixture.SOURCE_CODE);
        SourceReadService.SourceInterval second = read.collectionInterval(CrawlFixture.SOURCE_CODE);
        assertThat(first).isEqualTo(second);
        // The fixture leaves subscription_effective_to NULL, so the default end is +1 day.
        assertThat(first.end()).isEqualTo(first.start().plus(Duration.ofDays(1)));
    }
}
