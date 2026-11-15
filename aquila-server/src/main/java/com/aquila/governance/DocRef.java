package com.aquila.governance;

/** Typed reference passed to the commit barrier; sourceCode is needed to re-check source grants. */
public record DocRef(
        String docId,
        String docRevisionId,
        String sourceCode,
        long revisionOrder) {}
