package com.aquila.crawl;

import java.util.List;

/**
 * Bounded page of coverage facts for a source. The listing is paginated so a repeated run
 * (which can produce many pages) never returns an unbounded result, and each fact carries its
 * {@code runId} so the client can key rows by a stable, unique {@code (runId, pageIndex)}
 * composite rather than {@code pageIndex} alone (I6).
 */
public record CoverageFactPage(long total, int page, int size, List<CoverageFactDto> items) {}
