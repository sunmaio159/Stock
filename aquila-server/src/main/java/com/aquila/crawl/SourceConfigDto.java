package com.aquila.crawl;

import java.time.Instant;

/** Source configuration summary shown in the operations view. */
public record SourceConfigDto(
        String sourceCode,
        String sourceName,
        String permissionStatus,
        Instant validFrom,
        Instant validTo) {}
