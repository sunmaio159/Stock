package com.aquila.crawl;

import com.aquila.sourcepolicy.Permission;
import com.aquila.sourcepolicy.SourcePolicy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
}
