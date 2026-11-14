package com.aquila.crawl;

/** One raw item returned by a source adapter, identified by its stable outer id. */
public record RawRecord(String outerId, String payload) {}
