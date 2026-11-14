package com.aquila.crawl;

import java.util.List;

/**
 * Bounded page produced by a {@link SourceAdapter}. The adapter must declare, per page,
 * whether pagination has terminated, whether the source can prove completeness for the
 * interval (recent-N / untraceable sources must report {@code traceable=false}), and
 * whether a known unrecoverable gap exists.
 */
public record SourcePage(
        String sourceCode,
        int pageIndex,
        List<RawRecord> records,
        String nextCursor,
        boolean terminal,
        boolean unrecoverable,
        boolean traceable,
        String adapterContractVersion,
        Integer httpStatus,
        String contentType,
        Long byteSize,
        String terminalEvidence) {}
