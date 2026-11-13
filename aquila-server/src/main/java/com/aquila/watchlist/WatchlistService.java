package com.aquila.watchlist;

import com.aquila.common.AdminContext;
import com.aquila.common.ApiException;
import com.aquila.common.IdempotencyStore;
import com.aquila.config.MasterDataLimits;
import com.aquila.masterdata.SecurityCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class WatchlistService {
    private static final String SCOPE = "WATCHLIST_ADD";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AdminContext admin;
    private final MasterDataLimits limits;
    private final CapacityGuard capacityGuard;
    private final IdempotencyStore idempotency;
    private final ObjectMapper objectMapper;

    public WatchlistService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            AdminContext admin,
            MasterDataLimits limits,
            CapacityGuard capacityGuard,
            IdempotencyStore idempotency,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.admin = admin;
        this.limits = limits;
        this.capacityGuard = capacityGuard;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    public WatchlistGroup createGroup(String groupName) {
        String userId = admin.requireUserId();
        if (groupName == null || groupName.isBlank() || groupName.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid group name");
        }
        return transactions.execute(status -> {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM m1_watchlist_group WHERE user_id = ?",
                    Integer.class, userId);
            int size = count == null ? 0 : count;
            if (size >= limits.getMaxGroups()) {
                throw new ApiException(HttpStatus.CONFLICT, "Watchlist group limit reached");
            }
            String groupId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO m1_watchlist_group
                        (group_id, user_id, group_name, sort_order, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 0, NOW(6), NOW(6))
                    """, groupId, userId, groupName, size);
            return new WatchlistGroup(groupId, groupName, size, 0L);
        });
    }

    public List<WatchlistGroup> groups() {
        String userId = admin.requireUserId();
        return jdbc.query("""
                SELECT group_id, group_name, sort_order, version
                FROM m1_watchlist_group WHERE user_id = ?
                ORDER BY sort_order, group_id
                """, (resultSet, rowNumber) -> new WatchlistGroup(
                        resultSet.getString("group_id"),
                        resultSet.getString("group_name"),
                        resultSet.getInt("sort_order"),
                        resultSet.getLong("version")), userId);
    }

    public void deleteGroup(String groupId) {
        String userId = admin.requireUserId();
        transactions.executeWithoutResult(status -> {
            requireGroup(groupId, userId);
            jdbc.update("DELETE FROM m1_watchlist_item WHERE group_id = ?", groupId);
            jdbc.update("DELETE FROM m1_watchlist_group WHERE group_id = ?", groupId);
        });
    }

    public List<WatchlistItem> items(String groupId) {
        String userId = admin.requireUserId();
        requireGroup(groupId, userId);
        return jdbc.query("""
                SELECT item_id, group_id, security_code, sort_order
                FROM m1_watchlist_item WHERE group_id = ?
                ORDER BY sort_order, item_id
                """, (resultSet, rowNumber) -> new WatchlistItem(
                        resultSet.getString("item_id"),
                        resultSet.getString("group_id"),
                        resultSet.getString("security_code"),
                        resultSet.getInt("sort_order")), groupId);
    }

    public WatchlistItem add(String groupId, String securityCode, String idempotencyKey) {
        String canonical = canonical(securityCode);
        String userId = admin.requireUserId();
        String scopedKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? null : idempotency.scopedKey(SCOPE, idempotencyKey);
        String path = "/api/watchlist/groups/" + groupId + "/items";
        byte[] hash = idempotency.hash(groupId + "|" + canonical + "|" + idempotencyKey);
        capacityGuard.ensureLockRow(jdbc, userId);
        return transactions.execute(status -> {
            String stored = scopedKey == null
                    ? null : idempotency.find(jdbc, userId, scopedKey, path, hash);
            if (stored != null) {
                return decodeItem(stored);
            }

            requireGroup(groupId, userId);
            Set<String> distinct = capacityGuard.lockDistinct(jdbc, userId);
            if (scopedKey != null) {
                String concurrent = idempotency.findForUpdate(jdbc, userId, scopedKey, path, hash);
                if (concurrent != null) {
                    return decodeItem(concurrent);
                }
            }
            if (itemExists(groupId, canonical)) {
                throw new ApiException(HttpStatus.CONFLICT, "Security is already in this group");
            }
            if (!distinct.contains(canonical)
                    && CapacityPolicy.wouldExceed(
                            distinct.size(), limits.getWatchlistCapacity(), false)) {
                throw new ApiException(HttpStatus.CONFLICT, "Watchlist capacity reached");
            }
            if (!securityExists(canonical)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Security not found");
            }

            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM m1_watchlist_item WHERE group_id = ?",
                    Integer.class, groupId);
            int sortOrder = count == null ? 0 : count;
            String itemId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO m1_watchlist_item
                        (item_id, group_id, security_code, sort_order, created_at)
                    VALUES (?, ?, ?, ?, NOW(6))
                    """, itemId, groupId, canonical, sortOrder);

            WatchlistItem item = new WatchlistItem(itemId, groupId, canonical, sortOrder);
            if (scopedKey != null) {
                idempotency.store(jdbc, userId, scopedKey, path, hash, encodeItem(item));
            }
            return item;
        });
    }

    public void removeItem(String itemId) {
        String userId = admin.requireUserId();
        int removed = jdbc.update("""
                DELETE i FROM m1_watchlist_item i
                JOIN m1_watchlist_group g ON g.group_id = i.group_id
                WHERE i.item_id = ? AND g.user_id = ?
                """, itemId, userId);
        if (removed != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Watchlist item not found");
        }
    }

    public WatchlistGroup reorder(String groupId, long expectedVersion, List<String> orderedItemIds) {
        String userId = admin.requireUserId();
        if (orderedItemIds == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Item ids are required");
        }
        return transactions.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE m1_watchlist_group
                    SET version = version + 1, updated_at = NOW(6)
                    WHERE group_id = ? AND user_id = ? AND version = ?
                    """, groupId, userId, expectedVersion);
            if (updated != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "Watchlist group version conflict");
            }
            List<String> owned = jdbc.queryForList("""
                    SELECT item_id FROM m1_watchlist_item WHERE group_id = ? FOR UPDATE
                    """, String.class, groupId);
            Set<String> requested = new HashSet<>(orderedItemIds);
            if (requested.size() != orderedItemIds.size()
                    || !new HashSet<>(owned).equals(requested)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST, "Reorder must list every group item exactly once");
            }
            int order = 0;
            for (String itemId : orderedItemIds) {
                jdbc.update("""
                        UPDATE m1_watchlist_item SET sort_order = ?
                        WHERE item_id = ? AND group_id = ?
                        """, order++, itemId, groupId);
            }
            return groups().stream()
                    .filter(group -> groupId.equals(group.groupId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Watchlist group not found"));
        });
    }

    public HotlistItem addHotlist(String securityCode) {
        String canonical = canonical(securityCode);
        String userId = admin.requireUserId();
        capacityGuard.ensureLockRow(jdbc, userId);
        return transactions.execute(status -> {
            capacityGuard.lock(jdbc, userId);
            Integer existing = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM m1_hotlist_item
                    WHERE user_id = ? AND security_code = ?
                    """, Integer.class, userId, canonical);
            if (existing != null && existing > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "Security is already in the hotlist");
            }
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM m1_hotlist_item WHERE user_id = ?",
                    Integer.class, userId);
            int size = count == null ? 0 : count;
            if (size >= limits.getHotlistCapacity()) {
                throw new ApiException(HttpStatus.CONFLICT, "Hotlist capacity reached");
            }
            if (!securityExists(canonical)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Security not found");
            }
            String itemId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO m1_hotlist_item
                        (item_id, user_id, security_code, source, sort_order, created_at)
                    VALUES (?, ?, ?, ?, ?, NOW(6))
                    """, itemId, userId, canonical, HotlistSource.MANUAL.name(), size);
            return new HotlistItem(itemId, canonical, HotlistSource.MANUAL.name(), size);
        });
    }

    public List<HotlistItem> hotlist() {
        String userId = admin.requireUserId();
        return jdbc.query("""
                SELECT item_id, security_code, source, sort_order
                FROM m1_hotlist_item WHERE user_id = ?
                ORDER BY sort_order, item_id
                """, (resultSet, rowNumber) -> new HotlistItem(
                        resultSet.getString("item_id"),
                        resultSet.getString("security_code"),
                        resultSet.getString("source"),
                        resultSet.getInt("sort_order")), userId);
    }

    public void removeHotlist(String itemId) {
        String userId = admin.requireUserId();
        int removed = jdbc.update(
                "DELETE FROM m1_hotlist_item WHERE item_id = ? AND user_id = ?", itemId, userId);
        if (removed != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Hotlist item not found");
        }
    }

    private WatchlistItem decodeItem(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!SCOPE.equals(node.path("type").asText())
                    || node.get("itemId") == null
                    || node.get("groupId") == null
                    || node.get("securityCode") == null
                    || node.get("sortOrder") == null) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "Idempotency record belongs to another operation");
            }
            return new WatchlistItem(
                    node.get("itemId").asText(),
                    node.get("groupId").asText(),
                    node.get("securityCode").asText(),
                    node.get("sortOrder").asInt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is not readable", exception);
        }
    }

    private String encodeItem(WatchlistItem item) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", SCOPE,
                    "itemId", item.itemId(),
                    "groupId", item.groupId(),
                    "securityCode", item.securityCode(),
                    "sortOrder", item.sortOrder()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot encode idempotency response", exception);
        }
    }

    private void requireGroup(String groupId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM m1_watchlist_group
                WHERE group_id = ? AND user_id = ?
                """, Integer.class, groupId, userId);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Watchlist group not found");
        }
    }

    private boolean itemExists(String groupId, String securityCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM m1_watchlist_item
                WHERE group_id = ? AND security_code = ?
                """, Integer.class, groupId, securityCode);
        return count != null && count > 0;
    }

    private boolean securityExists(String securityCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM m2_security WHERE security_code = ?",
                Integer.class, securityCode);
        return count != null && count > 0;
    }

    private static String canonical(String securityCode) {
        try {
            return SecurityCode.parse(securityCode).canonical();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid canonical security code");
        }
    }
}
