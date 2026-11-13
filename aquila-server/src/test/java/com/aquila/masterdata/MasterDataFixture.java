package com.aquila.masterdata;

import com.aquila.config.DatabaseTargetPolicy;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class MasterDataFixture {
    public static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    private MasterDataFixture() {}

    public static void register(DynamicPropertyRegistry properties) {
        properties.add("aquila.database.enabled", () -> "true");
        properties.add("spring.datasource.url", () -> System.getenv("STOCK_MYSQL_IT_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("STOCK_MYSQL_IT_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("STOCK_MYSQL_IT_PASSWORD"));
        properties.add("aquila.database.schema", () -> DatabaseTargetPolicy.GREENFIELD_SCHEMA);
        properties.add("aquila.database.url", () -> System.getenv("STOCK_MYSQL_IT_URL"));
        properties.add("aquila.database.username", () -> System.getenv("STOCK_MYSQL_IT_USER"));
        properties.add("aquila.database.password", () -> System.getenv("STOCK_MYSQL_IT_PASSWORD"));
        properties.add("spring.flyway.url", () -> System.getenv("STOCK_MYSQL_IT_FLYWAY_URL"));
        properties.add("spring.flyway.user", () -> System.getenv("STOCK_MYSQL_IT_FLYWAY_USER"));
        properties.add("spring.flyway.password", () -> System.getenv("STOCK_MYSQL_IT_FLYWAY_PASSWORD"));
        properties.add("aquila.security.allowed-origin", () -> "http://localhost:8080");
        properties.add("aquila.security.totp-encryption-key", () -> System.getenv("STOCK_TOTP_ENCRYPTION_KEY"));
        properties.add("spring.flyway.enabled", () -> "true");
    }

    public static void clearStageBTables(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM sys_import_row");
        jdbc.update("DELETE FROM sys_import_batch");
        jdbc.update("DELETE FROM sys_idempotency_record");
        jdbc.update("DELETE FROM m1_watchlist_preference");
        jdbc.update("DELETE FROM m1_watchlist_item");
        jdbc.update("DELETE FROM m1_watchlist_group");
        jdbc.update("DELETE FROM m1_hotlist_item");
        jdbc.update("DELETE FROM m1_capacity_lock");
        jdbc.update("DELETE FROM m2_security_alias");
        jdbc.update("DELETE FROM m2_security");
        jdbc.update("DELETE FROM m2_trade_calendar");
    }

    public static void clear(JdbcTemplate jdbc) {
        clearStageBTables(jdbc);
        jdbc.update("DELETE FROM sys_audit_log");
        jdbc.update("DELETE FROM sys_login_failure");
        jdbc.update("DELETE FROM sys_recovery_code");
        jdbc.update("DELETE FROM sys_recovery_code_batch");
        jdbc.update("DELETE FROM sys_reauth_context");
        jdbc.update("DELETE FROM sys_csrf_context");
        jdbc.update("DELETE FROM sys_user_session");
        jdbc.update("DELETE FROM sys_bootstrap_context");
        jdbc.update("DELETE FROM sys_user");
    }

    public static void reset(JdbcTemplate jdbc) {
        clear(jdbc);
        jdbc.update("""
                INSERT INTO sys_user
                    (user_id, singleton_key, username, password_hash,
                     totp_secret_encrypted, enabled, created_at, updated_at)
                VALUES (?, 1, 'task2-admin', 'test-hash', X'00', TRUE, NOW(6), NOW(6))
                """, USER_ID);
    }

    public static void insertSecurities(JdbcTemplate jdbc, int count) {
        for (int index = 0; index < count; index++) {
            jdbc.update("""
                    INSERT INTO m2_security
                        (security_code, exchange, code, security_name, security_type,
                         status, created_at, updated_at)
                    VALUES (?, 'SH', ?, ?, 'STOCK', 'ACTIVE', NOW(6), NOW(6))
                    """, code(index), bareCode(index), "测试证券" + index);
        }
    }

    public static void insertAlias(JdbcTemplate jdbc, String alias, String securityCode) {
        jdbc.update("""
                INSERT INTO m2_security_alias
                    (alias_id, security_code, alias_norm, alias_type, created_at)
                VALUES (?, ?, ?, 'NAME', NOW(6))
                """, UUID.randomUUID().toString(), securityCode, SecurityCode.normalizeAlias(alias));
    }

    public static String code(int index) {
        return "SH:" + bareCode(index);
    }

    private static String bareCode(int index) {
        return String.format("%06d", index);
    }
}
