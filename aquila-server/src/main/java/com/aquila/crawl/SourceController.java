package com.aquila.crawl;

import com.aquila.common.AdminApi;
import com.aquila.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    private final CrawlApplicationService crawl;
    private final AdminApi api;
    private final SourceReadService read;

    public SourceController(
            CrawlApplicationService crawl,
            AdminApi api,
            SourceReadService read) {
        this.crawl = crawl;
        this.api = api;
        this.read = read;
    }

    @GetMapping("/sources")
    public Result<List<SourceConfigDto>> sources(HttpServletRequest request) {
        return api.respond(request, read::displayableSources);
    }

    @GetMapping("/sources/{code}/coverage")
    public Result<CoverageFactPage> coverage(
            @PathVariable("code") String code,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            HttpServletRequest request) {
        return api.respond(request, () -> read.coverageFacts(code, page, size));
    }

    @PostMapping("/sources/{code}/collect")
    public Result<CrawlResult> collect(
            @PathVariable("code") String code,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "start", required = false) String start,
            @RequestParam(name = "end", required = false) String end,
            HttpServletRequest request) {
        return api.respond(request, () -> {
            // A manual trigger that omits the interval must still map to a deterministic,
            // source-derived window (the source's effective range) so repeated triggers over the
            // same business interval share a run_key (idempotency / resume). An explicit interval
            // always wins.
            SourceReadService.SourceInterval interval = read.collectionInterval(code);
            Instant endInstant = end == null ? interval.end() : Instant.parse(end);
            Instant startInstant = start == null ? interval.start() : Instant.parse(start);
            return crawl.runCollection(
                    code, scope == null ? "DEFAULT" : scope, startInstant, endInstant);
        });
    }
}
