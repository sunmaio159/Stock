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
        List<PageVerdict> pages = jdbc.query("""
                SELECT coverage_status, cursor_before, cursor_after, evidence_reference
                FROM m3_coverage_fact WHERE run_id = ? ORDER BY page_index
                """, (resultSet, rowNumber) -> new PageVerdict(
                        resultSet.getString("coverage_status"),
                        resultSet.getString("cursor_before"),
                        resultSet.getString("cursor_after"),
                        resultSet.getString("evidence_reference")), runId);
        Tally tally = tally(pages);
        return new CoverageState(
                runId, resolveOverall(pages), tally.total(), tally.complete(), tally.partial(),
                tally.unknown(), tally.unrecoverable(), Instant.now());
    }

    /**
     * Resolves the run-level verdict from the page verdicts, in order of page index. Kept
     * free of database access so that the classification itself — including the branch for
     * a status outside the known vocabulary — can be exercised directly rather than relying
     * on the storage CHECK constraint to make it unreachable.
     */
    static String resolveOverall(List<PageVerdict> pages) {
        Tally tally = tally(pages);
        if (tally.total() == 0 || tally.unclassified() > 0) {
            return "UNKNOWN";
        }
        if (tally.unrecoverable() > 0) {
            return "UNRECOVERABLE";
        }
        if (tally.unknown() > 0) {
            return "UNKNOWN";
        }
        if (tally.partial() > 0) {
            return "PARTIAL";
        }
        // Completeness is proved, not inferred: the run must show a terminal page with
        // non-null terminal evidence and an unbroken cursor chain across that proof.
        if (!terminalPageProved(pages) || !cursorChainIsContinuous(pages)) {
            return "UNKNOWN";
        }
        return "COMPLETE";
    }

    private static Tally tally(List<PageVerdict> pages) {
        int complete = 0;
        int partial = 0;
        int unknown = 0;
        int unrecoverable = 0;
        int unclassified = 0;
        for (PageVerdict page : pages) {
            switch (page.status()) {
                case "COMPLETE" -> complete++;
                case "PARTIAL" -> partial++;
                case "UNKNOWN" -> unknown++;
                case "UNRECOVERABLE" -> unrecoverable++;
                // An unrecognised status is never counted towards a positive verdict, even
                // if the storage constraint that currently prevents it is relaxed.
                default -> unclassified++;
            }
        }
        return new Tally(
                pages.size(), complete, partial, unknown, unrecoverable, unclassified);
    }

    /**
     * A terminal page proves completeness only if it is the page the run actually stopped
     * on. An earlier page that merely looked terminal — because a contract version emitted a
     * null cursor without ending, or a row was written out of order — proves nothing about
     * the rest of the interval, so only the last page in index order is consulted.
     */
    private static boolean terminalPageProved(List<PageVerdict> pages) {
        PageVerdict last = pages.get(pages.size() - 1);
        return "COMPLETE".equals(last.status())
                && last.cursorAfter() == null
                && last.evidenceReference() != null;
    }

    /**
     * The chain has to be continuous from the interval boundary, not merely internally
     * consistent. A run that starts at a mid-stream cursor leaves everything before it
     * uncovered, so its first page must begin with no cursor at all.
     */
    private static boolean cursorChainIsContinuous(List<PageVerdict> pages) {
        if (pages.get(0).cursorBefore() != null) {
            return false;
        }
        for (int index = 1; index < pages.size(); index++) {
            if (!Objects.equals(pages.get(index - 1).cursorAfter(), pages.get(index).cursorBefore())) {
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

    /** One page's persisted verdict, the input to the run-level completeness proof. */
    record PageVerdict(
            String status, String cursorBefore, String cursorAfter, String evidenceReference) {}

    /** Page counts by status; {@code unclassified} is the outside-the-vocabulary count. */
    private record Tally(
            int total, int complete, int partial, int unknown, int unrecoverable, int unclassified) {}
}
