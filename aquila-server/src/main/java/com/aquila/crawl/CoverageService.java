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
                     observed_at, page_index, page_terminal, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6))
                ON DUPLICATE KEY UPDATE
                    coverage_status = VALUES(coverage_status),
                    reason = VALUES(reason),
                    cursor_before = VALUES(cursor_before),
                    cursor_after = VALUES(cursor_after),
                    business_interval_start = VALUES(business_interval_start),
                    business_interval_end = VALUES(business_interval_end),
                    evidence_reference = VALUES(evidence_reference),
                    observed_at = VALUES(observed_at),
                    page_terminal = VALUES(page_terminal),
                    adapter_contract_version = VALUES(adapter_contract_version)
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
                commit.pageIndex(),
                commit.terminal());
        return computeOverall(commit.runId());
    }

    public CoverageState computeOverall(String runId) {
        List<PageVerdict> pages = jdbc.query("""
                SELECT coverage_status, cursor_before, cursor_after, evidence_reference,
                       page_terminal
                FROM m3_coverage_fact WHERE run_id = ? ORDER BY page_index
                """, (resultSet, rowNumber) -> new PageVerdict(
                        resultSet.getString("coverage_status"),
                        resultSet.getString("cursor_before"),
                        resultSet.getString("cursor_after"),
                        resultSet.getString("evidence_reference"),
                        resultSet.getBoolean("page_terminal")), runId);
        Tally tally = tally(pages);
        return new CoverageState(
                runId, resolveOverall(tally, pages), tally.total(), tally.complete(), tally.partial(),
                tally.unknown(), tally.unrecoverable(), Instant.now());
    }

    /**
     * Resolves the run-level verdict from the page verdicts, in order of page index. Kept
     * free of database access so that the classification itself — including the branch for
     * a status outside the known vocabulary — can be exercised directly rather than relying
     * on the storage CHECK constraint to make it unreachable.
     */
    static String resolveOverall(List<PageVerdict> pages) {
        return resolveOverall(tally(pages), pages);
    }

    private static String resolveOverall(Tally tally, List<PageVerdict> pages) {
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
     * Completeness is proven only by the page the run actually terminated on, identified by
     * the persisted {@code page_terminal} flag rather than inferred from an empty cursor —
     * third-round finding G-1 was precisely that a successful non-terminal page also carries
     * the COMPLETE page status, so a null cursor could not tell the two apart. Only the last
     * page in index order is consulted, and it must carry the flag, the evidence reference,
     * and no next cursor.
     */
    private static boolean terminalPageProved(List<PageVerdict> pages) {
        PageVerdict last = pages.get(pages.size() - 1);
        return "COMPLETE".equals(last.status())
                && last.pageTerminal()
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
            String status, String cursorBefore, String cursorAfter, String evidenceReference,
            boolean pageTerminal) {}

    /** Page counts by status; {@code unclassified} is the outside-the-vocabulary count. */
    private record Tally(
            int total, int complete, int partial, int unknown, int unrecoverable, int unclassified) {}
}
