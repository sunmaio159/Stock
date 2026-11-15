package com.aquila.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.ApiException;
import com.aquila.crawl.JobLease;
import com.aquila.crawl.JobOutcome;
import com.aquila.crawl.ProcessingJobWorker;
import com.aquila.masterdata.MasterDataFixture;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * FT-03 falsifying tests: a revoke or an expired fencing token between computation and
 * commit must reject the derived write and leave no processed content behind.
 *
 * <p>The hook runs inside the worker transaction, but its own writes use a separate
 * physical connection and are committed immediately. That makes these real races rather
 * than staged state changes; the guard's locking reads are what make the concurrent
 * commit visible under REPEATABLE READ.
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LifecycleRaceIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private RevisionService revisions;
    @Autowired private LifecycleGuard guard;
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
        worker.setBeforeCommitHook(null);
        GovernanceFixture.clear(jdbc);
    }

    @Test
    void validLeaseCommitsDerivedWrites() {
        Fixture fixture = prepare();
        JobLease lease = worker.claim(fixture.jobId());
        assertThat(lease).isNotNull();

        JobOutcome outcome = worker.process(lease);

        assertThat(outcome.state()).isEqualTo("COMPLETE");
        assertThat(jobState(fixture.jobId())).isEqualTo("COMPLETE");
        assertThat(revisionStatus(fixture.revision().docRevisionId())).isEqualTo("PROCESSED");
        assertThat(processedOrder(fixture.revision().docId())).isEqualTo(1);
        assertThat(dirtyMentions(fixture.revision().docRevisionId())).isZero();
    }

    @Test
    void revocationBeforeCommitBlocksDerivedWrites() {
        Fixture fixture = prepare();
        JobLease lease = worker.claim(fixture.jobId());
        assertThat(lease).isNotNull();

        worker.setBeforeCommitHook(() -> {
            updateOnSeparateConnection("""
                    UPDATE m4_normalized_doc
                    SET lifecycle_status = 'REVOKED',
                        lifecycle_version = lifecycle_version + 1
                    WHERE doc_id = ?
                    """, fixture.revision().docId());
            updateOnSeparateConnection("""
                    UPDATE m3_source_config SET permission_status = 'REVOKED'
                    WHERE source_code = ?
                    """, GovernanceFixture.SOURCE_CODE);
        });

        JobOutcome outcome = worker.process(lease);

        assertThat(outcome.state()).isEqualTo("REVOKED");
        assertThat(jobState(fixture.jobId())).isEqualTo("REVOKED");
        assertThat(revisionStatus(fixture.revision().docRevisionId())).isEqualTo("OBSERVED");
        assertThat(processedOrder(fixture.revision().docId())).isZero();
        assertThat(dirtyMentions(fixture.revision().docRevisionId())).isEqualTo(1);
        assertThat(outcome.outputReference()).isNull();

        long captured = capturedLifecycleVersion(fixture.jobId());
        DocRef ref = new DocRef(fixture.revision().docId(), fixture.revision().docRevisionId(),
                GovernanceFixture.SOURCE_CODE, fixture.revision().revisionOrder());
        assertThatThrownBy(() -> guard.assertCommittable(ref, captured))
                .isInstanceOf(LifecycleBlockedException.class);
    }

    @Test
    void staleFencingTokenRejectsDerivedWrites() {
        Fixture fixture = prepare();
        JobLease lease = worker.claim(fixture.jobId());
        assertThat(lease).isNotNull();

        worker.setBeforeCommitHook(() -> updateOnSeparateConnection("""
                UPDATE m3_processing_job SET fencing_token = fencing_token + 1
                WHERE job_id = ?
                """, fixture.jobId()));

        assertThatThrownBy(() -> worker.process(lease))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(revisionStatus(fixture.revision().docRevisionId())).isEqualTo("OBSERVED");
        assertThat(processedOrder(fixture.revision().docId())).isZero();
        assertThat(dirtyMentions(fixture.revision().docRevisionId())).isEqualTo(1);
        assertThat(jobState(fixture.jobId())).isEqualTo("CLAIMED");
    }

    private Fixture prepare() {
        String raw = GovernanceFixture.insertRawRecord(jdbc, "POST-LIFE", "hash-life");
        DocumentRevision revision = revisions.receive(GovernanceFixture.observed(
                "POST-LIFE", 1L, "hash-life", raw, GovernanceFixture.SECURITY_A));
        String jobId = jdbc.queryForObject("""
                SELECT job_id FROM m3_processing_job WHERE doc_revision_id = ?
                """, String.class, revision.docRevisionId());
        return new Fixture(revision, jobId);
    }

    /** A raw, non-Spring-managed connection: the worker transaction must not see it as its own. */
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
            throw new IllegalStateException("Concurrent revoke fixture failed", exception);
        }
    }

    private String jobState(String jobId) {
        return jdbc.queryForObject("SELECT state FROM m3_processing_job WHERE job_id = ?",
                String.class, jobId);
    }

    private long capturedLifecycleVersion(String jobId) {
        return jdbc.queryForObject("""
                SELECT captured_lifecycle_version FROM m3_processing_job WHERE job_id = ?
                """, Long.class, jobId);
    }

    private String revisionStatus(String docRevisionId) {
        return jdbc.queryForObject("""
                SELECT status FROM m4_doc_revision WHERE doc_revision_id = ?
                """, String.class, docRevisionId);
    }

    private long processedOrder(String docId) {
        return jdbc.queryForObject("""
                SELECT latest_processed_revision_order FROM m4_normalized_doc WHERE doc_id = ?
                """, Long.class, docId);
    }

    private int dirtyMentions(String docRevisionId) {
        Integer value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM m4_entity_mention
                WHERE doc_revision_id = ? AND is_dirty = TRUE
                """, Integer.class, docRevisionId);
        return value == null ? 0 : value;
    }

    private record Fixture(DocumentRevision revision, String jobId) {}
}
