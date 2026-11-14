package com.aquila.crawl;

/**
 * Versioned adapter that fetches bounded pages from a licensed source. Fetching and
 * parsing happen outside database transactions; the adapter never writes cursors.
 */
public interface SourceAdapter {
    SourcePage fetch(SourceFetchRequest request);
}
