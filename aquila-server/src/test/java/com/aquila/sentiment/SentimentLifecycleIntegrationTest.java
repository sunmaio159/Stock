package com.aquila.sentiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.crawl.ProcessingJobWorker;
import com.aquila.governance.GovernanceFixture;
import com.aquila.governance.LifecycleBlockedException;
import com.aquila.governance.RevisionService;
import com.aquila.masterdata.MasterDataFixture;
import com.aquila.sentiment.SentimentModel.SentimentResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
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
 * Stage D review 2026-11-15, C-3: a sentiment result is a derived write, so the full
 * Stage C commit barrier — document lifecycle and captured version, licensed-source
 * status/grants/validity window, content fingerprint, revision status — must run in the
 * SAME transaction that writes the row. Every case here leaves no result row behind.
 *
 * <p>The hook runs inside the analysis transaction but writes over a separate physical
 * connection, so these are real races against the guard's locking reads, the same
 * pattern as LifecycleRaceIntegrationTest.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SentimentLifecycleIntegrationTest {

    private static final String ENGINE_LEVEL = "DOCUMENT";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private RevisionService revisions;
    @Autowired private ProcessingJobWorker worker;
    @Autowired private SentimentAnalysisService analysis;

    private final SentimentEngine engine = new SentimentEngine();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MasterDataFixture.register(registry);
    }

    @BeforeEach
    void reset() {
        SentimentFixture.reset(jdbc);
        analysis.setBeforeCommitHook(null);
    }

    @AfterEach
    void cleanup() {
        analysis.setBeforeCommitHook(null);
        SentimentFixture.clear(jdbc);
    }

    @Test
    void aProcessedRevisionAcceptsTheResult() {
        Fixture fixture = prepare();

        assertThat(analysis.persistResult(ENGINE_LEVEL, fixture.result, fixture.lifecycleVersion))
                .isTrue();
        assertThat(resultCount()).isEqualTo(1);
    }

    @Test
    void revocationBeforeCommitBlocksTheSentimentResult() {
        Fixture fixture = prepare();
        analysis.setBeforeCommitHook(() -> updateOnSeparateConnection("""
                UPDATE m3_source_config SET permission_status = 'REVOKED'
                WHERE source_code = ?
                """, GovernanceFixture.SOURCE_CODE));

        assertThatThrownBy(() ->
                analysis.persistResult(ENGINE_LEVEL, fixture.result, fixture.lifecycleVersion))
                .isInstanceOf(LifecycleBlockedException.class);
        assertThat(resultCount()).isZero();
    }

    @Test
    void expiredValidityWindowBeforeCommitBlocksTheSentimentResult() {
        Fixture fixture = prepare();
        // Deterministic fully-past UTC window: comparing a DB UTC_TIMESTAMP against the
        // guard's JVM clock races cross-host drift at a 1s margin, so use a fixed past
        // window with the same "expired before commit" semantics.
        analysis.setBeforeCommitHook(() -> updateOnSeparateConnection("""
                UPDATE m3_source_config
                SET valid_from = '2020-01-01 00:00:00', valid_to = '2020-01-02 00:00:00'
                WHERE source_code = ?
                """, GovernanceFixture.SOURCE_CODE));

        assertThatThrownBy(() ->
                analysis.persistResult(ENGINE_LEVEL, fixture.result, fixture.lifecycleVersion))
                .isInstanceOf(LifecycleBlockedException.class);
        assertThat(resultCount()).isZero();
    }

    /**
     * Review 2026-11-16 false-positive-4: a pre-test out-of-band UPDATE proves the guard
     * exists but not that it reads CURRENT at commit time. This case now tampers over a
     * separate connection inside the analysis transaction, so the guard's FOR UPDATE must
     * see the uncommitted-outside change through the locking read.
     */
    @Test
    void tamperedRevisionContentBlocksTheSentimentResult() {
        Fixture fixture = prepare();
        analysis.setBeforeCommitHook(() -> updateOnSeparateConnection("""
                UPDATE m4_doc_revision SET body_hash = 'tampered' WHERE doc_revision_id = ?
                """, fixture.revisionId));

        assertThatThrownBy(() ->
                analysis.persistResult(ENGINE_LEVEL, fixture.result, fixture.lifecycleVersion))
                .isInstanceOf(LifecycleBlockedException.class)
                .hasMessageContaining("content");
        assertThat(resultCount()).isZero();
    }

    @Test
    void supersededRevisionCannotProduceASentimentResult() {
        Fixture fixture = prepare();
        analysis.setBeforeCommitHook(() -> updateOnSeparateConnection("""
                UPDATE m4_doc_revision SET status = 'SUPERSEDED' WHERE doc_revision_id = ?
                """, fixture.revisionId));

        assertThatThrownBy(() ->
                analysis.persistResult(ENGINE_LEVEL, fixture.result, fixture.lifecycleVersion))
                .isInstanceOf(LifecycleBlockedException.class)
                .hasMessageContaining("PROCESSED");
        assertThat(resultCount()).isZero();
    }

    @Test
    void unprocessedRevisionCannotProduceASentimentResult() {
        String revisionId = SentimentFixture.newRevision(jdbc, revisions);
        SentimentResult result = engine.evaluate(
                SentimentFixture.input(revisionId, "gain", GovernanceFixture.SECURITY_A),
                SentimentFixture.dictionary(), SentimentFixture.algorithm());

        assertThatThrownBy(() -> analysis.persistResult(
                ENGINE_LEVEL, result, SentimentFixture.lifecycleVersion(jdbc, revisionId)))
                .isInstanceOf(LifecycleBlockedException.class)
                .hasMessageContaining("PROCESSED");
        assertThat(resultCount()).isZero();
    }

    @Test
    void staleCapturedLifecycleVersionBlocksTheSentimentResult() {
        Fixture fixture = prepare();

        assertThatThrownBy(() ->
                analysis.persistResult(ENGINE_LEVEL, fixture.result, fixture.lifecycleVersion - 1))
                .isInstanceOf(LifecycleBlockedException.class)
                .hasMessageContaining("Lifecycle version");
        assertThat(resultCount()).isZero();
    }

    @Test
    void missingRevisionIsBlockedByTheEntryGate() {
        SentimentResult orphan = new SentimentResult(
                "revision-that-does-not-exist", SentimentFixture.ALGO_VERSION,
                SentimentFixture.DICT_VERSION, new java.math.BigDecimal("10.00"),
                new java.math.BigDecimal("0.5000"), null,
                com.aquila.sentiment.SentimentModel.Eligibility.ELIGIBLE, null,
                java.util.List.of("gain"), false);

        assertThatThrownBy(() -> analysis.persistResult(ENGINE_LEVEL, orphan, 1L))
                .isInstanceOf(LifecycleBlockedException.class);
        assertThat(resultCount()).isZero();
    }

    private Fixture prepare() {
        String revisionId = SentimentFixture.processedRevision(
                jdbc, revisions, worker, GovernanceFixture.SECURITY_A);
        SentimentResult result = engine.evaluate(
                SentimentFixture.input(revisionId, "gain", GovernanceFixture.SECURITY_A),
                SentimentFixture.dictionary(), SentimentFixture.algorithm());
        if (result.score() == null) {
            throw new IllegalStateException("Fixture text must produce a scored result");
        }
        return new Fixture(revisionId, result,
                SentimentFixture.lifecycleVersion(jdbc, revisionId));
    }

    /** A raw, non-Spring-managed connection: the analysis transaction must not see it as its own. */
    private void updateOnSeparateConnection(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < parameters.length; index++) {
                    statement.setObject(index + 1, parameters[index]);
                }
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Separate-connection fixture failed", exception);
        }
    }

    private int resultCount() {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM m5_sentiment_result", Integer.class);
        return value == null ? 0 : value;
    }

    private record Fixture(String revisionId, SentimentResult result, long lifecycleVersion) {}
}
