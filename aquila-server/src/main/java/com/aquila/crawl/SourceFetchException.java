package com.aquila.crawl;

/** Raised when an adapter fails to fetch or parse a page; treated as a page-level failure. */
public class SourceFetchException extends RuntimeException {
    public SourceFetchException(String message) {
        super(message);
    }

    public SourceFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
