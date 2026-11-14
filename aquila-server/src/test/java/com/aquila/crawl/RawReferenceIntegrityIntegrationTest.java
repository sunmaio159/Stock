package com.aquila.crawl;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Falsifying tests for finding I2: when a raw insert collides with the dedup key the
 * collection commit must return the already persisted reference (§8.2 step 6). A durable
 * job may never point at a raw record id that no row owns, otherwise the worker can never
 * complete it.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RawReferenceIntegrityIntegrationTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant FIRST_END = Instant.parse("2026-11-02T00:00:00Z");
    private static final Instant SECOND_START = Instant.parse("2026-11-03T00:00:00Z");
    private static final Instant SECOND_END = Instant.parse("2026-11-04T00:00:00Z");

    private static final String DANGLING_JOBS = """
            SELECT COUNT(*) FROM m3_processing_job j
            WHERE j.state = 'PENDING'
              AND NOT EXISTS (SELECT 1 FROM m3_raw_record r WHERE r.raw_record_id = j.raw_record_id)
            """;

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
    }

    @Test
    void rawDedupConflictReusesThePersistedRawReferenceForNewJobs() {
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(1, 2, CrawlFixture.ADAPTER_CONTRACT));
        crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", FIRST_START, FIRST_END);
        assertThat(worker.recover()).isEqualTo(2);

        // Same business records arrive again under a new processor version: the raw dedup key
        // collides while the job idempotency key does not, so a new job row is created.
        jdbc.update("UPDATE m3_source_config SET adapter_contract_version = ? WHERE source_code = ?",
                "fixture-2.0", CrawlFixture.SOURCE_CODE);
        adapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(1, 2, "fixture-2.0"));
        crawl.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", SECOND_START, SECOND_END);

        assertThat(danglingJobs()).isZero();
        assertThat(worker.recover()).isEqualTo(2);
    }

    private int danglingJobs() {
        Integer count = jdbc.queryForObject(DANGLING_JOBS, Integer.class);
        return count == null ? 0 : count;
    }
}
