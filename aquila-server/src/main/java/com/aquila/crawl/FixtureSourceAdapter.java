package com.aquila.crawl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Legal fixture adapter used until G02 authorizes a real source. Behavior is driven by a
 * per-source {@link FixtureSourcePlan} registered by tests (or a controlled seed), so the
 * same single instance can simulate complete, partial, unknown, unrecoverable, and
 * recent-N / untraceable scenarios without touching any network source.
 */
@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class FixtureSourceAdapter implements SourceAdapter {

    private final Map<String, FixtureSourcePlan> plans = new ConcurrentHashMap<>();

    public void registerPlan(String sourceCode, FixtureSourcePlan plan) {
        plans.put(sourceCode, plan);
    }

    public void clearPlans() {
        plans.clear();
    }

    @Override
    public SourcePage fetch(SourceFetchRequest request) {
        FixtureSourcePlan plan = plans.get(request.sourceCode());
        if (plan == null) {
            throw new SourceFetchException("No fixture plan registered for " + request.sourceCode());
        }
        int pageIndex = request.pageIndex();
        if (pageIndex == plan.failPageIndex()) {
            throw new SourceFetchException("Simulated fetch failure on page " + pageIndex);
        }
        boolean terminal = pageIndex >= plan.totalPages() - 1;
        boolean unrecoverable = pageIndex == plan.unrecoverablePageIndex();
        List<RawRecord> records = new ArrayList<>();
        for (int index = 0; index < plan.recordsPerPage(); index++) {
            String outerId = request.sourceCode() + "-r" + (pageIndex * plan.recordsPerPage() + index);
            records.add(new RawRecord(outerId, "payload-" + outerId));
        }
        String nextCursor = terminal ? null : "cursor-" + (pageIndex + 1);
        String terminalEvidence = terminal ? "terminal-page-" + pageIndex : null;
        return new SourcePage(
                request.sourceCode(),
                pageIndex,
                records,
                nextCursor,
                terminal,
                unrecoverable,
                plan.traceable(),
                plan.adapterContractVersion(),
                200,
                "application/json",
                (long) (records.size() * 32),
                terminalEvidence);
    }
}
