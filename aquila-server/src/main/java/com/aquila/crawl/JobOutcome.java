package com.aquila.crawl;

/** Result of processing a claimed job under its fencing token. */
public record JobOutcome(String jobId, String state, String outputReference) {}
