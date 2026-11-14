package com.aquila.crawl;

/**
 * Optional hook invoked inside the page-commit transaction, just before the coverage fact
 * is written. Tests use it to force a transaction rollback and prove the cursor and raw
 * writes are not persisted on failure.
 */
public interface PageCommitHook {
    PageCommitHook NOOP = pageIndex -> { };

    void beforeCommit(int pageIndex);
}
