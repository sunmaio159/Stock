package com.aquila.crawl;

import java.time.Instant;

/**
 * Per-page coverage input consumed by {@link CoverageService#commitPage}. The service
 * decides the page status from outcome/terminal/unrecoverable/traceable and never infers
 * COMPLETE unless the page terminated and the source can prove completeness.
 */
public record PageCommit(
        String runId,
        String sourceCode,
        String subscriptionScope,
        int subscriptionVersion,
        Instant businessIntervalStart,
        Instant businessIntervalEnd,
        String cursorBefore,
        String cursorAfter,
        int pageIndex,
        PageOutcome outcome,
        boolean terminal,
        boolean unrecoverable,
        boolean traceable,
        String adapterContractVersion,
        String evidenceReference,
        String reason) {}
