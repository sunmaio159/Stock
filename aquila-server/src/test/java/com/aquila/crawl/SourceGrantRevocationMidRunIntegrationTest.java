package com.aquila.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.ApiException;
import com.aquila.masterdata.MasterDataFixture;
import com.aquila.sourcepolicy.SourcePolicy;
import java.time.Instant;
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
 * Falsifying tests for round-2 finding F-1: the unified design §8.1 requires the licence to
 * be re-checked before the write, "not only at scheduling time". A multi-page run must
 * therefore stop at the page whose capability was withdrawn mid-run, and the rejected
 * page must leave no raw record, durable job, coverage fact or cursor advance behind.
 *
 * <p>These cases fail on a run whose permission checks sit only before the page loop,
 * because every later page is then written without any further check.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SourceGrantRevocationMidRunIntegrationTest {

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
    void analyzeGrantWithdrawnBeforeTheSecondPageRejectsThatPageCommit() {
        long cursorAfterFirstPage = twoPageRunWithGrantWithdrawnOnSecondFetch("perm_analyze");

        assertRejectedRunLeftOnlyTheFirstPage(cursorAfterFirstPage);
    }

    @Test
    void storeRawGrantWithdrawnBeforeTheSecondPageRejectsThatPageCommit() {
        long cursorAfterFirstPage = twoPageRunWithGrantWithdrawnOnSecondFetch("perm_store_raw");

        assertRejectedRunLeftOnlyTheFirstPage(cursorAfterFirstPage);
    }

    /**
     * Runs a two-page collection in which the named grant is withdrawn while page 1 is being
     * fetched, so the withdrawal can only be observed at that page's write point. The cursor
     * version is snapshotted after page 0 has committed and before the withdrawn grant is
     * applied, so the final comparison is against a page that really was accepted.
     *
     * @return the cursor version observed immediately after page 0 committed
     */
    private long twoPageRunWithGrantWithdrawnOnSecondFetch(String grantColumn) {
        // Two pages of one record each: page 0 must commit and the run must continue, so the
        // withdrawal is only observable at page 1's write point. A single-page plan would
        // terminate on page 0 and never reach the check under test.
        fixtureAdapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(2, 1, CrawlFixture.ADAPTER_CONTRACT));

        long[] cursorAfterFirstPage = new long[1];
        SourceAdapter revokeOnSecondFetch = request -> {
            if (request.pageIndex() == 1) {
                // Page 0 has committed by now, so this snapshot is taken after a page that
                // really was accepted, and the withdrawal lands before page 1 writes.
                cursorAfterFirstPage[0] = cursorVersion();
                jdbc.update("UPDATE m3_source_config SET " + grantColumn
                        + " = FALSE WHERE source_code = ?", CrawlFixture.SOURCE_CODE);
            }
            return fixtureAdapter.fetch(request);
        };
        CrawlApplicationService service = new CrawlApplicationService(
                jdbc, transactions, policy, revokeOnSecondFetch, coverage, worker);

        assertThatThrownBy(() -> service.runCollection(
                CrawlFixture.SOURCE_CODE, "DEFAULT", START, END))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN));
        return cursorAfterFirstPage[0];
    }

    private void assertRejectedRunLeftOnlyTheFirstPage(long cursorAfterFirstPage) {
        assertThat(count("SELECT COUNT(*) FROM m3_coverage_fact"))
                .as("only the page committed before the withdrawal survives")
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM m3_raw_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM m3_processing_job")).isEqualTo(1);
        assertThat(cursorVersion())
                .as("the cursor advanced for page 0 only")
                .isEqualTo(cursorAfterFirstPage);
    }

    private long cursorVersion() {
        return jdbc.queryForObject(
                "SELECT cursor_version FROM m3_source_config WHERE source_code = ?",
                Long.class, CrawlFixture.SOURCE_CODE);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
