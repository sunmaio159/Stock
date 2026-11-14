package com.aquila.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.ApiException;
import com.aquila.masterdata.MasterDataFixture;
import com.aquila.sourcepolicy.SourcePolicy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Falsifying tests for finding C1: the page commit must be rejected once another worker
 * has taken the source lease. The run keeps the fencing token it was issued at claim time;
 * a takeover invalidates it, so the stale owner's page commit must fail and must not leave
 * any cursor, raw or coverage write behind.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CrawlStaleLeaseIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T00:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private SourcePolicy policy;
    @Autowired private CoverageService coverage;
    @Autowired private ProcessingJobWorker worker;
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
    void staleLeaseOwnerCannotCommitPageAfterTakeover() {
        fixtureAdapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(1, 2, CrawlFixture.ADAPTER_CONTRACT));
        long cursorBefore = cursorVersion();

        // The takeover happens after this worker claimed the lease and fetched the page,
        // but before its page-commit transaction — the exact stale-owner window §8.2 covers.
        SourceAdapter takeoverOnFetch = request -> {
            jdbc.update("""
                    UPDATE m3_source_config
                    SET lease_owner = 'worker-B', fencing_token = fencing_token + 1,
                        lease_until = DATE_ADD(NOW(6), INTERVAL 10 MINUTE), updated_at = NOW(6)
                    WHERE source_code = ?
                    """, CrawlFixture.SOURCE_CODE);
            return fixtureAdapter.fetch(request);
        };
        CrawlApplicationService staleWorker = new CrawlApplicationService(
                jdbc, transactions, policy, takeoverOnFetch, coverage, worker);

        assertThatThrownBy(() -> staleWorker.runCollection(
                CrawlFixture.SOURCE_CODE, "DEFAULT", START, END))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(cursorVersion()).isEqualTo(cursorBefore);
        assertThat(count("SELECT COUNT(*) FROM m3_raw_record")).isZero();
        assertThat(count("SELECT COUNT(*) FROM m3_coverage_fact")).isZero();
        assertThat(leaseOwner()).isEqualTo("worker-B");
    }

    private long cursorVersion() {
        return jdbc.queryForObject(
                "SELECT cursor_version FROM m3_source_config WHERE source_code = ?",
                Long.class, CrawlFixture.SOURCE_CODE);
    }

    private String leaseOwner() {
        List<String> owners = jdbc.query(
                "SELECT lease_owner FROM m3_source_config WHERE source_code = ?",
                (resultSet, rowNumber) -> resultSet.getString(1), CrawlFixture.SOURCE_CODE);
        return owners.isEmpty() ? null : owners.get(0);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
