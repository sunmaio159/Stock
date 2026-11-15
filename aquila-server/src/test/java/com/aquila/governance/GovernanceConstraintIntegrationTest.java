package com.aquila.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.ApiException;
import com.aquila.crawl.JobLease;
import com.aquila.crawl.JobOutcome;
import com.aquila.crawl.ProcessingJobWorker;
import com.aquila.masterdata.MasterDataFixture;
import java.time.Clock;
import java.time.Duration;
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
 * Constraint-level falsifying tests for the Stage C Task 2 as-built spec (owner decision D4).
 *
 * <p>These assert the as-built constraints, not facts a Java caller merely promises:
 * content-fingerprint verification before a derived commit, status CAS monotonicity, a
 * non-retryable outcome for a job whose revision disappeared, and the recompute-marker /
 * dirty-flag handoff staying in sync. Owner decision D4 records why a BEFORE UPDATE
 * trigger was rejected on this MySQL instance (error 1419, no SUPER privilege).
 */
@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GovernanceConstraintIntegrationTest {

    private static final String BODY_HASH = "hash-constraint";

    @Autowired private JdbcTemplate jdbc;
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
        worker.setClock(Clock.systemUTC());
    }

    @AfterEach
    void cleanup() {
        worker.setBeforeCommitHook(null);
        worker.setClock(Clock.systemUTC());
        GovernanceFixture.clear(jdbc);
    }

    @Test
    void tamperedRevisionContentCannotBeCommitted() {
        Fixture fixture = prepare();
        // A privileged, out-of-band UPDATE can change a content column; the point of
        // the fingerprint is that no derived commit can follow such a change.
        jdbc.update("""
                UPDATE m4_doc_revision SET body_hash = 'tampered' WHERE doc_revision_id = ?
                """, fixture.revision().docRevisionId());
        assertThat(fingerprintMatches(fixture.revision().docRevisionId())).isFalse();

        JobLease lease = worker.claim(fixture.jobId());
        assertThat(lease).isNotNull();
        JobOutcome outcome = worker.process(lease);

        assertThat(outcome.state()).isEqualTo("REVOKED");
        assertThat(jobState(fixture.jobId())).isEqualTo("REVOKED");
        assertThat(status(fixture.revision().docRevisionId())).isEqualTo("OBSERVED");
        assertThat(processedOrder(fixture.revision().docId())).isZero();
        assertThat(dirtyMentions(fixture.revision().docRevisionId())).isEqualTo(1);
    }

    /**
     * Review round-1 I-3: replaces the tautological test that re-ran the worker's own CAS.
     * A SUPERSEDED revision must not produce a derived commit; removing the worker's
     * status-CAS row-count check makes this red (outcome becomes COMPLETE).
     */
    @Test
    void supersededRevisionCannotProduceDerivedWrites() {
        Fixture fixture = prepare();
        String revisionId = fixture.revision().docRevisionId();
        jdbc.update("""
                UPDATE m4_doc_revision SET status = 'SUPERSEDED' WHERE doc_revision_id = ?
                """, revisionId);

        JobLease lease = worker.claim(fixture.jobId());
        assertThat(lease).isNotNull();
        JobOutcome outcome = worker.process(lease);

        assertThat(outcome.state()).isEqualTo("REVOKED");
        assertThat(jobState(fixture.jobId())).isEqualTo("REVOKED");
        assertThat(status(revisionId)).isEqualTo("SUPERSEDED");
        assertThat(processedOrder(fixture.revision().docId())).isZero();
        assertThat(dirtyMentions(revisionId)).isEqualTo(1);
        assertThat(unconsumedMarkers(revisionId)).isEqualTo(1);
        assertThat(bodyHash(revisionId)).isEqualTo(BODY_HASH);
        assertThat(fingerprintMatches(revisionId)).isTrue();
    }

    /**
     * Review round-1 I-1: a stale lease must be reclaimable once it expires, rather than
     * stranding the job in CLAIMED forever.
     */
    @Test
    void expiredClaimedJobIsReclaimedAndCompleted() {
        Fixture fixture = prepare();
        JobLease oldLease = worker.claim(fixture.jobId());
        assertThat(oldLease).isNotNull();
        jdbc.update("""
                UPDATE m3_processing_job SET fencing_token = fencing_token + 1 WHERE job_id = ?
                """, fixture.jobId());
        assertThatThrownBy(() -> worker.process(oldLease)).isInstanceOf(ApiException.class);
        assertThat(jobState(fixture.jobId())).isEqualTo("CLAIMED");

        worker.setClock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(11)));

        assertThat(worker.recover()).isEqualTo(1);
        assertThat(jobState(fixture.jobId())).isEqualTo("COMPLETE");
        assertThat(status(fixture.revision().docRevisionId())).isEqualTo("PROCESSED");
    }

    /** Review round-1 I-1: one failing job must not abort the rest of a recovery batch. */
    @Test
    void oneBadJobDoesNotAbortTheRecoveryBatch() {
        Fixture fixture = prepare();
        // Deliberately older created_at so this job is scanned first. Its dangling raw
        // record makes process() throw ApiException; without a per-job catch the good
        // job below would never be reached.
        jdbc.update("""
                INSERT INTO m3_processing_job
                    (job_id, input_type, input_key, source_code, raw_record_id, fetched_date,
                     stage, processor_version, input_revision, state, fencing_token,
                     retry_count, created_at, updated_at)
                SELECT UUID(), input_type, CONCAT(input_key, '-bad'), source_code,
                       'raw-missing', fetched_date, stage, processor_version, input_revision,
                       'PENDING', 0, 0, DATE_SUB(NOW(6), INTERVAL 1 DAY), NOW(6)
                FROM m3_processing_job WHERE job_id = ?
                """, fixture.jobId());

        assertThat(worker.recover()).isEqualTo(1);
        assertThat(jobState(fixture.jobId())).isEqualTo("COMPLETE");
    }

    @Test
    void jobWithMissingRevisionBecomesNonRetryableTerminal() {
        Fixture fixture = prepare();
        jdbc.update("""
                UPDATE m3_processing_job SET doc_revision_id = ? WHERE job_id = ?
                """, "revision-no-longer-present", fixture.jobId());

        JobLease lease = worker.claim(fixture.jobId());
        assertThat(lease).isNotNull();
        JobOutcome outcome = worker.process(lease);

        assertThat(outcome.state()).isEqualTo("REVOKED");
        assertThat(jobState(fixture.jobId())).isEqualTo("REVOKED");
        assertThat(status(fixture.revision().docRevisionId())).isEqualTo("OBSERVED");
        assertThat(processedOrder(fixture.revision().docId())).isZero();
    }

    @Test
    void markerConsumptionStaysInSyncWithDirtyHandoff() {
        Fixture fixture = prepare();
        String revisionId = fixture.revision().docRevisionId();
        assertThat(unconsumedMarkers(revisionId)).isEqualTo(1);
        assertThat(dirtyMentions(revisionId)).isEqualTo(1);

        JobLease lease = worker.claim(fixture.jobId());
        assertThat(lease).isNotNull();
        assertThat(worker.process(lease).state()).isEqualTo("COMPLETE");

        assertThat(dirtyMentions(revisionId)).isZero();
        assertThat(unconsumedMarkers(revisionId)).isZero();
    }

    /**
     * Review round-2 Important-2: I-5's SourcePolicy gate needs its own falsifying test.
     * Removing the require() calls makes this red.
     */
    @Test
    void receiveRefusesRevokedSourceBeforeAnyWrite() {
        jdbc.update("""
                UPDATE m3_source_config SET permission_status = 'REVOKED' WHERE source_code = ?
                """, GovernanceFixture.SOURCE_CODE);
        String raw = GovernanceFixture.insertRawRecord(jdbc, "POST-POLICY-A", BODY_HASH);
        ObservedDocument document = GovernanceFixture.observed(
                "POST-POLICY-A", 1L, BODY_HASH, raw, GovernanceFixture.SECURITY_A);

        assertThatThrownBy(() -> revisions.receive(document))
                .isInstanceOf(ApiException.class)
                .satisfies(cause -> assertThat(((ApiException) cause).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(countRevisions()).isZero();
        assertThat(countDocJobs()).isZero();
    }

    /** A missing per-capability grant must fail closed even when the source is ACTIVE. */
    @Test
    void receiveRefusesSourceWithoutNormalizedStorageGrant() {
        jdbc.update("""
                UPDATE m3_source_config SET perm_store_normalized = FALSE WHERE source_code = ?
                """, GovernanceFixture.SOURCE_CODE);
        String raw = GovernanceFixture.insertRawRecord(jdbc, "POST-POLICY-B", BODY_HASH);
        ObservedDocument document = GovernanceFixture.observed(
                "POST-POLICY-B", 1L, BODY_HASH, raw, GovernanceFixture.SECURITY_A);

        assertThatThrownBy(() -> revisions.receive(document))
                .isInstanceOf(ApiException.class)
                .satisfies(cause -> assertThat(((ApiException) cause).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(countRevisions()).isZero();
        assertThat(countDocJobs()).isZero();
    }

    private int countRevisions() {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM m4_doc_revision", Integer.class);
        return value == null ? 0 : value;
    }

    private int countDocJobs() {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM m3_processing_job WHERE doc_revision_id IS NOT NULL",
                Integer.class);
        return value == null ? 0 : value;
    }

    private Fixture prepare() {
        String raw = GovernanceFixture.insertRawRecord(jdbc, "POST-CONSTRAINT", BODY_HASH);
        DocumentRevision revision = revisions.receive(GovernanceFixture.observed(
                "POST-CONSTRAINT", 1L, BODY_HASH, raw, GovernanceFixture.SECURITY_A));
        String jobId = jdbc.queryForObject("""
                SELECT job_id FROM m3_processing_job WHERE doc_revision_id = ?
                """, String.class, revision.docRevisionId());
        return new Fixture(revision, jobId);
    }

    private String bodyHash(String revisionId) {
        return jdbc.queryForObject("""
                SELECT body_hash FROM m4_doc_revision WHERE doc_revision_id = ?
                """, String.class, revisionId);
    }

    private boolean fingerprintMatches(String revisionId) {
        Boolean value = jdbc.queryForObject("""
                SELECT content_fingerprint = SHA2(CONCAT_WS('|',
                           doc_id, revision_order, body_hash, raw_record_id, fetched_date,
                           published_at, observed_at, normalizer_version, parser_version), 256)
                FROM m4_doc_revision WHERE doc_revision_id = ?
                """, Boolean.class, revisionId);
        return Boolean.TRUE.equals(value);
    }

    private String status(String revisionId) {
        return jdbc.queryForObject("""
                SELECT status FROM m4_doc_revision WHERE doc_revision_id = ?
                """, String.class, revisionId);
    }

    private String jobState(String jobId) {
        return jdbc.queryForObject("SELECT state FROM m3_processing_job WHERE job_id = ?",
                String.class, jobId);
    }

    private long processedOrder(String docId) {
        return jdbc.queryForObject("""
                SELECT latest_processed_revision_order FROM m4_normalized_doc WHERE doc_id = ?
                """, Long.class, docId);
    }

    private int dirtyMentions(String revisionId) {
        Integer value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM m4_entity_mention
                WHERE doc_revision_id = ? AND is_dirty = TRUE
                """, Integer.class, revisionId);
        return value == null ? 0 : value;
    }

    private int unconsumedMarkers(String revisionId) {
        Integer value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM m4_recompute_marker
                WHERE doc_revision_id = ? AND consumed_at IS NULL
                """, Integer.class, revisionId);
        return value == null ? 0 : value;
    }

    private record Fixture(DocumentRevision revision, String jobId) {}
}
