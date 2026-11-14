package com.aquila.crawl;

import com.aquila.common.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes durable PARSE jobs. After a page commit, jobs are left PENDING; a missed
 * in-memory notification is recovered by scanning the durable job table. A job is claimed
 * with a lease and a monotonically increasing fencing token; an expired lease can be
 * reclaimed by another worker, and the stale owner can no longer commit its outcome.
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class ProcessingJobWorker {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String workerOwner = UUID.randomUUID().toString();
    private final Duration leaseDuration = Duration.ofMinutes(10);

    public ProcessingJobWorker(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** Scan all durable PENDING jobs and process them; the recovery path for missed notifications. */
    public int recover() {
        List<String> pending = jdbc.query(
                "SELECT job_id FROM m3_processing_job WHERE state = 'PENDING' ORDER BY created_at, job_id",
                (resultSet, rowNumber) -> resultSet.getString("job_id"));
        int processed = 0;
        for (String jobId : pending) {
            JobLease lease = claim(jobId);
            if (lease != null && process(lease) != null) {
                processed++;
            }
        }
        return processed;
    }

    public JobLease claim(String jobId) {
        return transactions.execute(status -> {
            Instant now = Instant.now();
            int updated = jdbc.update("""
                    UPDATE m3_processing_job
                    SET state = 'CLAIMED', lease_owner = ?, lease_until = ?, fencing_token = fencing_token + 1,
                        updated_at = NOW(6)
                    WHERE job_id = ? AND (state = 'PENDING' OR (state = 'CLAIMED' AND lease_until < ?))
                    """, workerOwner, now.plus(leaseDuration), jobId, now);
            if (updated != 1) {
                return null;
            }
            long fencing = jdbc.queryForObject(
                    "SELECT fencing_token FROM m3_processing_job WHERE job_id = ?",
                    Long.class, jobId);
            return new JobLease(jobId, fencing);
        });
    }

    public JobOutcome process(JobLease lease) {
        return transactions.execute(status -> {
            List<String> rawIds = jdbc.query(
                    "SELECT raw_record_id FROM m3_processing_job "
                            + "WHERE job_id = ? AND state = 'CLAIMED' AND fencing_token = ?",
                    (resultSet, rowNumber) -> resultSet.getString("raw_record_id"),
                    lease.jobId(), lease.fencingToken());
            if (rawIds.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "Job is not claimable under this lease");
            }
            String rawRecordId = rawIds.get(0);
            int exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM m3_raw_record WHERE raw_record_id = ?",
                    Integer.class, rawRecordId);
            if (exists == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "Referenced raw record is missing");
            }
            int updated = jdbc.update("""
                    UPDATE m3_processing_job
                    SET state = 'COMPLETE', output_reference = ?, updated_at = NOW(6)
                    WHERE job_id = ? AND state = 'CLAIMED' AND fencing_token = ?
                    """, rawRecordId, lease.jobId(), lease.fencingToken());
            if (updated != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "Job outcome commit rejected");
            }
            return new JobOutcome(lease.jobId(), "COMPLETE", rawRecordId);
        });
    }
}
