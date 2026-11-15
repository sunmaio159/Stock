package com.aquila.crawl;

import com.aquila.common.ApiException;
import com.aquila.sourcepolicy.Permission;
import com.aquila.sourcepolicy.SourcePolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read side of the source operations API. Per the unified design §8.1 the DISPLAY
 * permission is re-checked at the point of use rather than only at scheduling time, so no
 * read path bypasses {@link SourcePolicy}: a single source is refused outright, and the
 * source catalogue is filtered per source because one caller may hold display on some
 * sources only.
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class SourceReadService {

    private final JdbcTemplate jdbc;
    private final SourcePolicy policy;

    public SourceReadService(JdbcTemplate jdbc, SourcePolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    public List<SourceConfigDto> displayableSources() {
        List<SourceConfigDto> rows = jdbc.query("""
                SELECT source_code, source_name, permission_status, valid_from, valid_to
                FROM m3_source_config ORDER BY source_code
                """, (resultSet, rowNumber) -> new SourceConfigDto(
                        resultSet.getString("source_code"),
                        resultSet.getString("source_name"),
                        resultSet.getString("permission_status"),
                        resultSet.getTimestamp("valid_from").toInstant(),
                        resultSet.getTimestamp("valid_to") == null
                                ? null : resultSet.getTimestamp("valid_to").toInstant()));
        List<SourceConfigDto> visible = new ArrayList<>();
        for (SourceConfigDto row : rows) {
            if (policy.permits(Permission.DISPLAY, row.sourceCode())) {
                visible.add(row);
            }
        }
        return visible;
    }

    public List<CoverageFactDto> coverageFacts(String sourceCode) {
        policy.require(Permission.DISPLAY, sourceCode);
        return jdbc.query("""
                SELECT page_index, business_interval_start, business_interval_end,
                       coverage_status, reason, evidence_reference, observed_at
                FROM m3_coverage_fact WHERE source_code = ? ORDER BY page_index
                """, (resultSet, rowNumber) -> new CoverageFactDto(
                        resultSet.getInt("page_index"),
                        resultSet.getTimestamp("business_interval_start") == null ? null
                                : resultSet.getTimestamp("business_interval_start").toInstant(),
                        resultSet.getTimestamp("business_interval_end") == null ? null
                                : resultSet.getTimestamp("business_interval_end").toInstant(),
                        resultSet.getString("coverage_status"),
                        resultSet.getString("reason"),
                        resultSet.getString("evidence_reference"),
                        resultSet.getTimestamp("observed_at").toInstant()),
                sourceCode);
    }

    /**
     * Deterministic collection window for a manual "collect" trigger that does not specify an
     * interval. Derived from the source's subscription effective range so that repeated operator
     * triggers over the same business interval share a run_key (idempotency / resume), instead of
     * each call inventing a unique {@code Instant.now()} interval. A null {@code effective_to}
     * falls back to a one-day window anchored at {@code effective_from}.
     */
    public record SourceInterval(Instant start, Instant end) {}

    public SourceInterval collectionInterval(String sourceCode) {
        policy.require(Permission.ACCESS, sourceCode);
        List<SourceInterval> rows = jdbc.query(
                "SELECT subscription_effective_from, subscription_effective_to"
                        + " FROM m3_source_config WHERE source_code = ?",
                (resultSet, rowNumber) -> new SourceInterval(
                        resultSet.getTimestamp("subscription_effective_from").toInstant(),
                        resultSet.getTimestamp("subscription_effective_to") == null
                                ? null : resultSet.getTimestamp("subscription_effective_to").toInstant()),
                sourceCode);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "source not found: " + sourceCode);
        }
        SourceInterval row = rows.get(0);
        Instant start = row.start();
        Instant end = row.end() == null ? start.plus(Duration.ofDays(1)) : row.end();
        return new SourceInterval(start, end);
    }
}
