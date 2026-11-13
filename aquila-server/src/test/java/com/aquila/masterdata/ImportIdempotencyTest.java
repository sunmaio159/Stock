package com.aquila.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.AuthMySqlCondition;
import com.aquila.common.AdminContext;
import com.aquila.common.ApiException;
import com.aquila.common.Page;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ImportIdempotencyTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminContext admin;
    @Autowired private SecurityService securities;
    @Autowired private ImportService imports;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        MasterDataFixture.register(properties);
    }

    @BeforeEach
    void reset() {
        MasterDataFixture.reset(jdbc);
        MasterDataFixture.insertSecurities(jdbc, 25);
        MasterDataFixture.insertAlias(jdbc, "浦发银行", MasterDataFixture.code(0));
    }

    @AfterEach
    void cleanup() {
        MasterDataFixture.clear(jdbc);
    }

    @Test
    void aliasResolvesToTheCanonicalSecurity() {
        Page<SecurityDto> page = securities.search("浦发银行", 0, 10);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(SecurityDto::securityCode)
                .containsExactly(MasterDataFixture.code(0));
    }

    @Test
    void canonicalCodeSearchAndPaginationAreStable() {
        Page<SecurityDto> firstPage = securities.search("", 0, 2);
        Page<SecurityDto> byCode = securities.search("sh:000000", 0, 10);

        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.total()).isEqualTo(25);
        assertThat(byCode.items()).extracting(SecurityDto::securityCode)
                .containsExactly(MasterDataFixture.code(0));
    }

    @Test
    void previewRecordsRowErrorsWithoutWritingWatchlistItems() {
        String content = MasterDataFixture.code(0) + "\n600001\nBJ:600000\n ";

        ImportPreview preview = admin.as(MasterDataFixture.USER_ID,
                () -> imports.preview("watchlist.txt", content));

        assertThat(preview.rows()).hasSize(4);
        assertThat(preview.rowCount()).isEqualTo(4);
        assertThat(preview.errorCount()).isEqualTo(3);
        assertThat(preview.rows().get(0).normalizedCode()).isEqualTo(MasterDataFixture.code(0));
        assertThat(preview.rows().get(1).errorCode()).isEqualTo(ImportErrorCode.MISSING_MARKET);
        assertThat(preview.rows().get(2).errorCode()).isEqualTo(ImportErrorCode.INVALID_MARKET);
        assertThat(preview.rows().get(3).errorCode()).isEqualTo(ImportErrorCode.EMPTY_ROW);
        assertThat(count("SELECT COUNT(*) FROM sys_import_row WHERE batch_id = ?", preview.batchId()))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m1_watchlist_item", Integer.class)).isZero();
    }

    @Test
    void commitIsIdempotentForTheSameKey() {
        String content = MasterDataFixture.code(0) + "\n" + MasterDataFixture.code(1);
        ImportPreview preview = admin.as(MasterDataFixture.USER_ID,
                () -> imports.preview("watchlist.txt", content));

        ImportResult first = admin.as(MasterDataFixture.USER_ID,
                () -> imports.commit(preview.batchId(), "commit-key-1"));
        ImportResult second = admin.as(MasterDataFixture.USER_ID,
                () -> imports.commit(preview.batchId(), "commit-key-1"));

        assertThat(first.committedRows()).isEqualTo(2);
        assertThat(first.status()).isEqualTo("COMMITTED");
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m1_watchlist_item", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM sys_import_batch WHERE batch_id = ?", String.class, preview.batchId()))
                .isEqualTo("COMMITTED");
    }

    @Test
    void alreadyCommittedBatchRejectsADifferentKey() {
        ImportPreview preview = admin.as(MasterDataFixture.USER_ID,
                () -> imports.preview("watchlist.txt", MasterDataFixture.code(0)));
        admin.as(MasterDataFixture.USER_ID, () -> imports.commit(preview.batchId(), "commit-key-1"));

        assertThatThrownBy(() -> admin.as(MasterDataFixture.USER_ID,
                () -> imports.commit(preview.batchId(), "commit-key-2")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void idempotencyKeyCannotBeReusedForADifferentBatch() {
        ImportPreview first = admin.as(MasterDataFixture.USER_ID,
                () -> imports.preview("first.txt", MasterDataFixture.code(0)));
        ImportPreview second = admin.as(MasterDataFixture.USER_ID,
                () -> imports.preview("second.txt", MasterDataFixture.code(1)));
        admin.as(MasterDataFixture.USER_ID, () -> imports.commit(first.batchId(), "shared-key"));

        assertThatThrownBy(() -> admin.as(MasterDataFixture.USER_ID,
                () -> imports.commit(second.batchId(), "shared-key")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void concurrentCommitsWithTheSameKeyReplayOneResult() throws Exception {
        ImportPreview preview = admin.as(MasterDataFixture.USER_ID,
                () -> imports.preview("race.txt",
                        MasterDataFixture.code(0) + "\n" + MasterDataFixture.code(1)));
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<ImportResult> results = new ArrayList<>();
        try {
            Callable<ImportResult> attempt = () -> {
                start.await();
                return admin.as(MasterDataFixture.USER_ID,
                        () -> imports.commit(preview.batchId(), "concurrent-key"));
            };
            List<Future<ImportResult>> futures = List.of(
                    pool.submit(attempt), pool.submit(attempt));
            start.countDown();
            for (Future<ImportResult> future : futures) {
                results.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(results.get(0).committedRows()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m1_watchlist_item", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void searchEscapesLikeWildcardsAndToleratesFullWidthSpace() {
        assertThat(securities.search("%", 0, 10).items()).isEmpty();
        assertThat(securities.search("_", 0, 10).items()).isEmpty();
        assertThat(securities.search("\u3000", 0, 10).items()).isEmpty();
    }

    @Test
    void oversizedFileNameAndIdempotencyKeyAreRejected() {
        ImportPreview preview = admin.as(MasterDataFixture.USER_ID,
                () -> imports.preview("watchlist.txt", MasterDataFixture.code(0)));

        assertThatThrownBy(() -> admin.as(MasterDataFixture.USER_ID,
                () -> imports.preview("x".repeat(256), MasterDataFixture.code(0))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> admin.as(MasterDataFixture.USER_ID,
                () -> imports.commit(preview.batchId(), "k".repeat(101))))
                .isInstanceOf(ApiException.class);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }
}
