package com.aquila.crawl;

/** Proof that a worker claimed a job under a specific fencing token. */
public record JobLease(String jobId, long fencingToken) {}
