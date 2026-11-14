package com.aquila.crawl;

/**
 * Controls a {@link FixtureSourceAdapter} for one source. {@code failPageIndex} and
 * {@code unrecoverablePageIndex} use -1 to mean "does not apply". A non-traceable plan
 * (recent-N / untraceable source) must never let the run be declared COMPLETE.
 */
public record FixtureSourcePlan(
        int totalPages,
        int recordsPerPage,
        int failPageIndex,
        int unrecoverablePageIndex,
        boolean traceable,
        String adapterContractVersion) {

    public static FixtureSourcePlan traceable(int totalPages, int recordsPerPage, String contract) {
        return new FixtureSourcePlan(totalPages, recordsPerPage, -1, -1, true, contract);
    }
}
