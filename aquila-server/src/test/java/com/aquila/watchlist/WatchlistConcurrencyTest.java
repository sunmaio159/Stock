package com.aquila.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.AdminContext;
import com.aquila.common.ApiException;
import com.aquila.config.MasterDataLimits;
import com.aquila.masterdata.MasterDataFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WatchlistConcurrencyTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminContext admin;
    @Autowired private WatchlistService watchlists;
    @Autowired private MasterDataLimits limits;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        MasterDataFixture.register(properties);
    }

    @BeforeEach
    void reset() {
        MasterDataFixture.reset(jdbc);
        MasterDataFixture.insertSecurities(jdbc, 30);
    }

    @AfterEach
    void cleanup() {
        MasterDataFixture.clear(jdbc);
    }

    @Test
    void duplicateItemInTheSameGroupIsRejected() {
        String groupId = createGroup("duplicates").groupId();
        as(() -> watchlists.add(groupId, MasterDataFixture.code(0), null));

        assertThatThrownBy(() -> as(() -> watchlists.add(groupId, MasterDataFixture.code(0), null)))
                .isInstanceOf(ApiException.class);
        assertThat(distinctInDatabase()).isEqualTo(1);
    }

    @Test
    void sameSecurityInTwoGroupsCountsOnceTowardCapacity() {
        String first = createGroup("A").groupId();
        String second = createGroup("B").groupId();
        as(() -> watchlists.add(first, MasterDataFixture.code(0), null));
        as(() -> watchlists.add(second, MasterDataFixture.code(0), null));
        assertThat(distinctInDatabase()).isEqualTo(1);

        for (int index = 1; index < limits.getWatchlistCapacity(); index++) {
            String code = MasterDataFixture.code(index);
            as(() -> watchlists.add(first, code, null));
        }
        String overflow = MasterDataFixture.code(limits.getWatchlistCapacity());
        assertThatThrownBy(() -> as(() -> watchlists.add(second, overflow, null)))
                .isInstanceOf(ApiException.class);
        assertThat(distinctInDatabase()).isEqualTo(limits.getWatchlistCapacity());
    }

    @Test
    void staleExpectedVersionIsRejected() {
        WatchlistGroup group = createGroup("ordering");
        WatchlistItem item = as(() -> watchlists.add(group.groupId(), MasterDataFixture.code(0), null));

        WatchlistGroup updated = as(() -> watchlists.reorder(
                group.groupId(), group.version(), List.of(item.itemId())));

        assertThat(updated.version()).isEqualTo(group.version() + 1);
        assertThatThrownBy(() -> as(() -> watchlists.reorder(
                group.groupId(), group.version(), List.of(item.itemId()))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void manualHotlistStaysSeparateFromCandidates() {
        HotlistItem entry = as(() -> watchlists.addHotlist(MasterDataFixture.code(0)));

        assertThat(entry.source()).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM m1_hotlist_item WHERE source <> 'MANUAL'", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO m1_hotlist_item
                    (item_id, user_id, security_code, source, sort_order, created_at)
                VALUES (?, ?, ?, 'CANDIDATE', 0, NOW(6))
                """, UUID.randomUUID().toString(), MasterDataFixture.USER_ID,
                MasterDataFixture.code(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentAddsCannotExceedDistinctSecurityCapacity() throws Exception {
        int capacity = limits.getWatchlistCapacity();
        String groupId = createGroup("race").groupId();
        for (int index = 0; index < capacity - 1; index++) {
            String code = MasterDataFixture.code(index);
            as(() -> watchlists.add(groupId, code, null));
        }

        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Boolean> results = new ArrayList<>();
        try {
            List<Callable<Boolean>> attempts = List.of(
                    () -> {
                        start.await();
                        return tryAdd(groupId, MasterDataFixture.code(capacity - 1));
                    },
                    () -> {
                        start.await();
                        return tryAdd(groupId, MasterDataFixture.code(capacity));
                    });
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> attempt : attempts) {
                futures.add(pool.submit(attempt));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(distinctInDatabase()).isEqualTo(capacity);
    }

    @Test
    void concurrentHotlistAddsCannotExceedCapacity() throws Exception {
        int capacity = limits.getHotlistCapacity();
        for (int index = 0; index < capacity - 1; index++) {
            String code = MasterDataFixture.code(index);
            as(() -> watchlists.addHotlist(code));
        }

        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Boolean> results = new ArrayList<>();
        try {
            List<Callable<Boolean>> attempts = List.of(
                    () -> {
                        start.await();
                        return tryAddHotlist(MasterDataFixture.code(capacity - 1));
                    },
                    () -> {
                        start.await();
                        return tryAddHotlist(MasterDataFixture.code(capacity));
                    });
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> attempt : attempts) {
                futures.add(pool.submit(attempt));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM m1_hotlist_item WHERE user_id = ?",
                Integer.class, MasterDataFixture.USER_ID)).isEqualTo(capacity);
    }

    @Test
    void reorderRejectsAnIncompleteItemSetWithoutChangingVersion() {
        WatchlistGroup group = createGroup("incomplete-set");
        as(() -> watchlists.add(group.groupId(), MasterDataFixture.code(0), null));
        WatchlistItem second = as(() -> watchlists.add(
                group.groupId(), MasterDataFixture.code(1), null));

        assertThatThrownBy(() -> as(() -> watchlists.reorder(
                group.groupId(), group.version(), List.of(second.itemId()))))
                .isInstanceOf(ApiException.class);
        long versionAfter = as(() -> watchlists.groups()).stream()
                .filter(entry -> entry.groupId().equals(group.groupId()))
                .findFirst()
                .orElseThrow()
                .version();
        assertThat(versionAfter).isEqualTo(group.version());
    }

    @Test
    void idempotencyKeyIsBoundToItsRequest() {
        String first = createGroup("A").groupId();
        String second = createGroup("B").groupId();
        as(() -> watchlists.add(first, MasterDataFixture.code(0), "shared-key"));

        assertThatThrownBy(() -> as(() -> watchlists.add(
                second, MasterDataFixture.code(1), "shared-key")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void concurrentAddsWithTheSameKeyReplayOneItem() throws Exception {
        String groupId = createGroup("add-replay").groupId();
        String code = MasterDataFixture.code(0);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<WatchlistItem> results = new ArrayList<>();
        try {
            Callable<WatchlistItem> attempt = () -> {
                start.await();
                return as(() -> watchlists.add(groupId, code, "add-replay-key"));
            };
            List<Future<WatchlistItem>> futures = List.of(
                    pool.submit(attempt), pool.submit(attempt));
            start.countDown();
            for (Future<WatchlistItem> future : futures) {
                results.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(distinctInDatabase()).isEqualTo(1);
    }

    private boolean tryAddHotlist(String code) {
        try {
            as(() -> watchlists.addHotlist(code));
            return true;
        } catch (ApiException exception) {
            return false;
        }
    }

    private boolean tryAdd(String groupId, String code) {
        try {
            as(() -> watchlists.add(groupId, code, null));
            return true;
        } catch (ApiException exception) {
            return false;
        }
    }

    private WatchlistGroup createGroup(String name) {
        return as(() -> watchlists.createGroup(name));
    }

    private <T> T as(java.util.function.Supplier<T> action) {
        return admin.as(MasterDataFixture.USER_ID, action);
    }

    private long distinctInDatabase() {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT i.security_code)
                FROM m1_watchlist_item i
                JOIN m1_watchlist_group g ON g.group_id = i.group_id
                WHERE g.user_id = ?
                """, Long.class, MasterDataFixture.USER_ID);
        return value == null ? 0L : value;
    }
}
