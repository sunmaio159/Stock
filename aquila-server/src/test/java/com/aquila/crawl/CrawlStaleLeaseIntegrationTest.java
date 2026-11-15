package com.aquila.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.ApiException;
import com.aquila.masterdata.MasterDataFixture;
import com.aquila.sourcepolicy.SourcePolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    @Test
    void secondWorkerCannotClaimTheLeaseWhileItIsStillHeld() {
        fixtureAdapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(1, 1, CrawlFixture.ADAPTER_CONTRACT));
        // Each service instance is a distinct worker: leaseOwner is per instance.
        CrawlApplicationService holder = new CrawlApplicationService(
                jdbc, transactions, policy, fixtureAdapter, coverage, worker);
        holder.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, END);

        // Round-1 C1 root cause (b) was that two workers could claim the same
        // non-monotonic fencing token, because the lease was read without a lock and
        // claimed unconditionally. A live lease held by someone else must be refused.
        CrawlApplicationService contender = new CrawlApplicationService(
                jdbc, transactions, policy, fixtureAdapter, coverage, worker);

        assertThatThrownBy(() -> contender.runCollection(
                CrawlFixture.SOURCE_CODE, "DEFAULT", START, END.plus(Duration.ofHours(6))))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(count("SELECT COUNT(*) FROM m3_coverage_fact")).isEqualTo(1);
        assertThat(count("SELECT DISTINCT fencing_token FROM m3_task_instance")).isEqualTo(1);
    }

    /**
     * Round-3 finding G-4: the two tests above are sequential, so they guard the live-lease
     * refusal but never exercise the actual simultaneous race that round-1 C1 root cause (b)
     * described — several workers reading the lease without a lock and issuing the same next
     * fencing token. Here multiple workers hit the claim at once against the same source.
     * The {@code FOR UPDATE} lock plus the {@code WHERE fencing_token = issuedToken - 1} CAS
     * must let exactly one worker take the lease and force every other concurrent claim into a
     * conflict, so a single fencing token and a single run's pages are all that survive.
     */
    @Test
    void simultaneousClaimsProduceExactlyOneLeaseOwner() throws Exception {
        fixtureAdapter.registerPlan(CrawlFixture.SOURCE_CODE,
                FixtureSourcePlan.traceable(1, 1, CrawlFixture.ADAPTER_CONTRACT));

        int workers = 4;
        CrawlApplicationService[] services = new CrawlApplicationService[workers];
        for (int index = 0; index < workers; index++) {
            // Each service instance is a distinct worker: leaseOwner is per instance.
            services[index] = new CrawlApplicationService(
                    jdbc, transactions, policy, fixtureAdapter, coverage, worker);
        }

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Optional<HttpStatus>>> attempts = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                final CrawlApplicationService service = services[index];
                // A distinct interval end gives a distinct run key, so no worker is short-
                //circuited by idempotency and all four contend for the lease itself.
                final Instant intervalEnd = END.plus(Duration.ofHours(6L * (index + 1)));
                attempts.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        service.runCollection(CrawlFixture.SOURCE_CODE, "DEFAULT", START, intervalEnd);
                        return Optional.of(HttpStatus.OK);
                    } catch (ApiException exception) {
                        return Optional.of(exception.status());
                    }
                }));
            }
            startGate.countDown();

            int succeeded = 0;
            int conflicted = 0;
            for (Future<Optional<HttpStatus>> attempt : attempts) {
                HttpStatus status = attempt.get(30, TimeUnit.SECONDS).orElse(null);
                if (status == HttpStatus.OK) {
                    succeeded++;
                } else if (status == HttpStatus.CONFLICT) {
                    conflicted++;
                }
            }

            assertThat(succeeded).isEqualTo(1);
            assertThat(conflicted).isEqualTo(workers - 1);
            assertThat(count("SELECT COUNT(DISTINCT fencing_token) FROM m3_task_instance")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM m3_coverage_fact")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
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
