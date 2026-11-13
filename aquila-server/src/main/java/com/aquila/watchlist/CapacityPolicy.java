package com.aquila.watchlist;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public final class CapacityPolicy {
    private CapacityPolicy() {}

    public static long distinctCount(Collection<? extends Collection<String>> groupedCodes) {
        Set<String> distinct = new HashSet<>();
        for (Collection<String> codes : groupedCodes) {
            distinct.addAll(codes);
        }
        return distinct.size();
    }

    public static boolean wouldExceed(long currentDistinct, long limit, boolean alreadyPresent) {
        return !alreadyPresent && currentDistinct >= limit;
    }
}
