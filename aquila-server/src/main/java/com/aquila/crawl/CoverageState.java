package com.aquila.crawl;

import java.time.Instant;

/** Aggregated coverage verdict for one collection run. */
public record CoverageState(
        String runId,
        String overallStatus,
        int pagesTotal,
        int pagesComplete,
        int pagesPartial,
        int pagesUnknown,
        int pagesUnrecoverable,
        Instant observedAt) {}
