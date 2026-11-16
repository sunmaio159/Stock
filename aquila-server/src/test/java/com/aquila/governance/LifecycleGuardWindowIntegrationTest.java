package com.aquila.governance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.crawl.ProcessingJobWorker;
import com.aquila.masterdata.MasterDataFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * Review 2026-11-16 Important-2: LifecycleGuard previously shared SourcePolicy's boundary
 * semantics only by comment — its fixed-clock seam had zero callers and the exact
 * {@code [valid_from, valid_to)} edges were never exercised at the commit barrier. These
 * cases drive the guard directly at the inclusive lower and exclusive upper bound, and add
 * a real-clock control that must be refused so a no-op clock cannot pass the suite.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LifecycleGuardWindowIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LifecycleGuard guard;
    @Autowired private RevisionService revisions;
    @Autowired private ProcessingJobWorker worker;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MasterDataFixture.register(registry);
    }

    @BeforeEach
    void reset() {
        GovernanceFixture.reset(jdbc);
        worker.setBeforeCommitHook(null);
    }

    @AfterEach
    void cleanup() {
        guard.setClock(Clock.systemUTC());
        worker.setBeforeCommitHook(null);
        GovernanceFixture.clear(jdbc);
    }

    private Instant today() {
        return Instant.now().truncatedTo(ChronoUnit.DAYS);
    }

    private Fixture prepare(Instant from, Instant to) {
        String raw = GovernanceFixture.insertRawRecord(jdbc, "POST-GUARD", "hash-guard");
        DocumentRevision revision = revisions.receive(GovernanceFixture.observed(
                "POST-GUARD", 1L, "hash-guard", raw, GovernanceFixture.SECURITY_A));
        jdbc.update("""
                UPDATE m3_source_config SET valid_from = ?, valid_to = ?
                WHERE source_code = ?
                """, LocalDateTime.ofInstant(from, ZoneOffset.UTC),
                LocalDateTime.ofInstant(to, ZoneOffset.UTC), GovernanceFixture.SOURCE_CODE);
        long captured = jdbc.queryForObject("""
                SELECT captured_lifecycle_version FROM m3_processing_job WHERE doc_revision_id = ?
                """, Long.class, revision.docRevisionId());
        return new Fixture(new DocRef(revision.docId(), revision.docRevisionId(),
                GovernanceFixture.SOURCE_CODE, revision.revisionOrder()), captured);
    }

    @Test
    void lowerBoundIsInclusiveAtTheCommitBarrier() {
        Instant from = today().minus(2, ChronoUnit.DAYS);
        Fixture fixture = prepare(from, today().minus(1, ChronoUnit.DAYS));
        guard.setClock(Clock.fixed(from, ZoneOffset.UTC));
        assertThatCode(() -> guard.assertCommittable(fixture.ref(), fixture.captured()))
                .doesNotThrowAnyException();
    }

    @Test
    void upperBoundIsExclusiveAtTheCommitBarrier() {
        Instant to = today().minus(1, ChronoUnit.DAYS);
        Fixture fixture = prepare(today().minus(2, ChronoUnit.DAYS), to);
        guard.setClock(Clock.fixed(to, ZoneOffset.UTC));
        assertThatThrownBy(() -> guard.assertCommittable(fixture.ref(), fixture.captured()))
                .isInstanceOf(LifecycleBlockedException.class);
    }

    @Test
    void realClockControlIsRefusedOutsideTheWindow() {
        // Fixed clock at the lower bound passes (see above); with the real clock still
        // inside that past window this case would pass for the wrong reason, so the window
        // here ends yesterday and today must be refused without any fixed clock.
        Fixture fixture = prepare(today().minus(3, ChronoUnit.DAYS),
                today().minus(2, ChronoUnit.DAYS));
        assertThatThrownBy(() -> guard.assertCommittable(fixture.ref(), fixture.captured()))
                .isInstanceOf(LifecycleBlockedException.class);
    }

    private record Fixture(DocRef ref, long captured) {}
}
