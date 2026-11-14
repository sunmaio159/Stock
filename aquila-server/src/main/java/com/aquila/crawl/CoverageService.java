package com.aquila.crawl;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists per-page coverage facts and aggregates the overall verdict for a run. A run is
 * COMPLETE only when every page succeeded, terminated, and the source can prove
 * completeness. Partial progress, unknown evidence, or unrecoverable gaps keep the run out
 * of COMPLETE; an untraceable (recent-N) source is explicitly prevented from COMPLETE.
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class CoverageService {

    private final JdbcTemplate jdbc;

    public CoverageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CoverageState commitPage(PageCommit commit) {
        String status = decidePageStatus(commit);
        String reason = decideReason(commit, status);
        jdbc.update("""
                INSERT INTO m3_coverage_fact
                    (coverage_id, source_code, run_id, subscription_scope, subscription_version,
                     business_interval_start, business_interval_end, cursor_before, cursor_after,
                     coverage_status, reason, adapter_contract_version, evidence_reference,
                     observed_at, page_index, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6))
                """,
                UUID.randomUUID().toString(),
                commit.sourceCode(),
                commit.runId(),
                commit.subscriptionScope(),
                commit.subscriptionVersion(),
                commit.businessIntervalStart(),
                commit.businessIntervalEnd(),
                commit.cursorBefore(),
                commit.cursorAfter(),
                status,
                reason,
                commit.adapterContractVersion(),
                commit.evidenceReference(),
                Instant.now(),
                commit.pageIndex());
        return computeOverall(commit.runId());
    }

    public CoverageState computeOverall(String runId) {
        List<CoverageRow> rows = jdbc.query("""
                SELECT coverage_status, cursor_before, cursor_after, evidence_reference
                FROM m3_coverage_fact WHERE run_id = ? ORDER BY page_index
                """, (resultSet, rowNumber) -> new CoverageRow(
                        resultSet.getString("coverage_status"),
                        resultSet.getString("cursor_before"),
                        resultSet.getString("cursor_after"),
                        resultSet.getString("evidence_reference")), runId);
        int total = rows.size();
        int complete = 0;
        int partial = 0;
        int unknown = 0;
        int unrecoverable = 0;
        int unclassified = 0;
        for (CoverageRow row : rows) {
            switch (row.status()) {
                case "COMPLETE" -> complete++;
                case "PARTIAL" -> partial++;
                case "UNKNOWN" -> unknown++;
                case "UNRECOVERABLE" -> unrecoverable++;
                // An unrecognised status is never allowed to reach the positive branch,
                // even if the storage constraint that currently prevents it is relaxed.
                default -> unclassified++;
            }
        }
        String overall;
        if (total == 0) {
            overall = "UNKNOWN";
        } else if (unclassified > 0) {
            overall = "UNKNOWN";
        } else if (unrecoverable > 0) {
            overall = "UNRECOVERABLE";
        } else if (unknown > 0) {
            overall = "UNKNOWN";
        } else if (partial > 0) {
            overall = "PARTIAL";
        } else if (!terminalPageProved(rows) || !cursorChainIsContinuous(rows)) {
            // Completeness is proved, not inferred: the run must show a terminal page with
            // non-null terminal evidence and an unbroken cursor chain across that proof.
            overall = "UNKNOWN";
        } else {
            overall = "COMPLETE";
        }
        return new CoverageState(
                runId, overall, total, complete, partial, unknown, unrecoverable, Instant.now());
    }

    private static boolean terminalPageProved(List<CoverageRow> rows) {
        for (CoverageRow row : rows) {
            if ("COMPLETE".equals(row.status())
                    && row.cursorAfter() == null
                    && row.evidenceReference() != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean cursorChainIsContinuous(List<CoverageRow> rows) {
        for (int index = 1; index < rows.size(); index++) {
            if (!Objects.equals(rows.get(index - 1).cursorAfter(), rows.get(index).cursorBefore())) {
                return false;
            }
        }
        return true;
    }

    private String decidePageStatus(PageCommit commit) {
        if (commit.unrecoverable()) {
            return "UNRECOVERABLE";
        }
        if (commit.outcome() == PageOutcome.FAILURE) {
            return "PARTIAL";
        }
        // Successfully fetched and persisted page. A non-terminal page is still COMPLETE at
        // the page level; only the run overall reflects whether the interval is finished.
        if (!commit.terminal()) {
            return "COMPLETE";
        }
        return commit.traceable() ? "COMPLETE" : "UNKNOWN";
    }

    private String decideReason(PageCommit commit, String status) {
        if (commit.reason() != null) {
            return commit.reason();
        }
        return switch (status) {
            case "PARTIAL" -> commit.outcome() == PageOutcome.FAILURE
                    ? "page fetch failed" : "page committed, interval not terminal";
            case "UNKNOWN" -> "source cannot prove completeness";
            case "COMPLETE" -> commit.terminal()
                    ? "interval terminal and source traceable" : "page fetched and persisted";
            case "UNRECOVERABLE" -> "known gap beyond recoverable scope";
            default -> null;
        };
    }

    private record CoverageRow(
            String status, String cursorBefore, String cursorAfter, String evidenceReference) {}
}
