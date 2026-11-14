package com.aquila.crawl;

import com.aquila.masterdata.MasterDataFixture;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test fixtures for Stage C Task 1: source configuration, reset, and cleanup. */
public final class CrawlFixture {

    public static final String SOURCE_CODE = "FIXTURE-SRC";
    public static final String ADAPTER_CONTRACT = "fixture-1.0";

    private CrawlFixture() {}

    public static void reset(JdbcTemplate jdbc) {
        clearStockTables(jdbc);
        MasterDataFixture.reset(jdbc);
        insertActiveSource(jdbc, SOURCE_CODE);
    }

    public static void clear(JdbcTemplate jdbc) {
        clearStockTables(jdbc);
        MasterDataFixture.clear(jdbc);
    }

    public static void insertActiveSource(JdbcTemplate jdbc, String sourceCode) {
        jdbc.update("""
                INSERT INTO m3_source_config
                    (source_code, source_name, permission_version, permission_status,
                     valid_from, valid_to, rate_limit, subscription_scope, subscription_version,
                     subscription_effective_from, subscription_effective_to, license_version,
                     cursor_version, lease_owner, lease_until, fencing_token,
                     adapter_contract_version, created_at, updated_at)
                VALUES (?, 'Fixture Source', 1, 'ACTIVE', '2020-01-01 00:00:00', NULL, 0, 'DEFAULT', 1,
                        NOW(6), NULL, 1, 0, NULL, NULL, 0, ?, NOW(6), NOW(6))
                """, sourceCode, ADAPTER_CONTRACT);
    }

    public static void insertRevokedSource(JdbcTemplate jdbc, String sourceCode) {
        jdbc.update("""
                INSERT INTO m3_source_config
                    (source_code, source_name, permission_version, permission_status,
                     valid_from, valid_to, rate_limit, subscription_scope, subscription_version,
                     subscription_effective_from, subscription_effective_to, license_version,
                     cursor_version, lease_owner, lease_until, fencing_token,
                     adapter_contract_version, created_at, updated_at)
                VALUES (?, 'Revoked Source', 1, 'REVOKED', '2020-01-01 00:00:00', NULL, 0, 'DEFAULT', 1,
                        NOW(6), NULL, 1, 0, NULL, NULL, 0, ?, NOW(6), NOW(6))
                """, sourceCode, ADAPTER_CONTRACT);
    }

    private static void clearStockTables(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM m3_coverage_fact");
        jdbc.update("DELETE FROM m3_adapter_evidence");
        jdbc.update("DELETE FROM m3_processing_job");
        jdbc.update("DELETE FROM m3_raw_record");
        jdbc.update("DELETE FROM m3_task_instance");
        jdbc.update("DELETE FROM m3_source_config");
    }
}
