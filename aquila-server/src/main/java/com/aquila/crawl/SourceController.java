package com.aquila.crawl;

import com.aquila.common.AdminApi;
import com.aquila.common.Result;
import com.aquila.sourcepolicy.SourcePolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class SourceController {

    private final SourcePolicy policy;
    private final CrawlApplicationService crawl;
    private final AdminApi api;
    private final JdbcTemplate jdbc;

    public SourceController(
            SourcePolicy policy,
            CrawlApplicationService crawl,
            AdminApi api,
            JdbcTemplate jdbc) {
        this.policy = policy;
        this.crawl = crawl;
        this.api = api;
        this.jdbc = jdbc;
    }

    @GetMapping("/sources")
    public Result<List<SourceConfigDto>> sources(HttpServletRequest request) {
        return api.respond(request, this::listSources);
    }

    @GetMapping("/sources/{code}/coverage")
    public Result<List<CoverageFactDto>> coverage(
            @PathVariable("code") String code, HttpServletRequest request) {
        return api.respond(request, () -> listCoverage(code));
    }

    @PostMapping("/sources/{code}/collect")
    public Result<CrawlResult> collect(
            @PathVariable("code") String code,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "start", required = false) String start,
            @RequestParam(name = "end", required = false) String end,
            HttpServletRequest request) {
        return api.respond(request, () -> {
            Instant endInstant = end == null ? Instant.now() : Instant.parse(end);
            Instant startInstant = start == null
                    ? endInstant.minus(Duration.ofDays(1)) : Instant.parse(start);
            return crawl.runCollection(
                    code, scope == null ? "DEFAULT" : scope, startInstant, endInstant);
        });
    }

    private List<SourceConfigDto> listSources() {
        return jdbc.query("""
                SELECT source_code, source_name, permission_status, valid_from, valid_to
                FROM m3_source_config ORDER BY source_code
                """, (resultSet, rowNumber) -> new SourceConfigDto(
                        resultSet.getString("source_code"),
                        resultSet.getString("source_name"),
                        resultSet.getString("permission_status"),
                        resultSet.getTimestamp("valid_from").toInstant(),
                        resultSet.getTimestamp("valid_to") == null
                                ? null : resultSet.getTimestamp("valid_to").toInstant()));
    }

    private List<CoverageFactDto> listCoverage(String code) {
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
                code);
    }
}
