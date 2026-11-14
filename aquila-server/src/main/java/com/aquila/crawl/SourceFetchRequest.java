package com.aquila.crawl;

import java.time.Instant;

/** Request handed to a {@link SourceAdapter} for one bounded page of a collection run. */
public record SourceFetchRequest(
        String sourceCode,
        String subscriptionScope,
        Instant businessIntervalStart,
        Instant businessIntervalEnd,
        String cursor,
        int pageIndex,
        int pageSize) {}
