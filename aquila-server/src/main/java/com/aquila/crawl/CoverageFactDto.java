package com.aquila.crawl;

import java.time.Instant;

/** Per-page coverage fact shown in the operations view. */
public record CoverageFactDto(
        String runId,
        int pageIndex,
        Instant intervalStart,
        Instant intervalEnd,
        String status,
        String reason,
        String evidenceReference,
        Instant observedAt) {}
