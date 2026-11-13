package com.aquila.watchlist;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CapacityGuard {
    public void ensureLockRow(JdbcTemplate jdbc, String userId) {
        jdbc.update("""
                INSERT IGNORE INTO m1_capacity_lock (user_id, updated_at) VALUES (?, NOW(6))
                """, userId);
    }

    public void lock(JdbcTemplate jdbc, String userId) {
        List<String> locks = jdbc.queryForList(
                "SELECT user_id FROM m1_capacity_lock WHERE user_id = ? FOR UPDATE",
                String.class, userId);
        if (locks.isEmpty()) {
            throw new IllegalStateException("Capacity lock row is missing for the administrator");
        }
    }

    public Set<String> lockDistinct(JdbcTemplate jdbc, String userId) {
        lock(jdbc, userId);
        List<String> codes = jdbc.queryForList("""
                SELECT i.security_code
                FROM m1_watchlist_item i
                JOIN m1_watchlist_group g ON g.group_id = i.group_id
                WHERE g.user_id = ? FOR UPDATE
                """, String.class, userId);
        return new HashSet<>(codes);
    }
}
