package com.aquila.governance;

/** Result of receiving an observed document into the immutable revision history. */
public record DocumentRevision(
        String docId,
        String docRevisionId,
        String sourceCode,
        String outerId,
        long revisionOrder,
        long latestObservedRevisionOrder) {}
