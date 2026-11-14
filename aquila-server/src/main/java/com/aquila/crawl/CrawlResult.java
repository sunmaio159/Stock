package com.aquila.crawl;

/** Outcome of one collection run, carrying the aggregated coverage verdict. */
public record CrawlResult(String runId, CoverageState coverage) {}
