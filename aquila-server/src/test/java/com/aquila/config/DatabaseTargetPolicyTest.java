package com.aquila.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class DatabaseTargetPolicyTest {
    private final DatabaseTargetPolicy policy = new DatabaseTargetPolicy();

    @Test
    void permitsOnlyTheExplicitGreenfieldSchema() {
        assertThat(policy.permits("stock_greenfield_20261109")).isTrue();
        assertThat(policy.permits("stock")).isFalse();
        assertThat(policy.permits("stock_greenfield_20261108")).isFalse();
        assertThat(policy.permits(null)).isFalse();
    }

    @Test
    void rejectsAnyConfiguredSchemaOutsideTheGreenfieldScope() {
        DatabaseTargetPolicy configured = new DatabaseTargetPolicy("stock", "", "", "");

        assertThatIllegalStateException()
                .isThrownBy(configured::validateConfiguration)
                .withMessageContaining("stock_greenfield_20261109");
    }

    @Test
    void requiresCredentialsWhenDatabaseConnectionIsConfigured() {
        DatabaseTargetPolicy configured = new DatabaseTargetPolicy(
                "stock_greenfield_20261109",
                "jdbc:mysql://127.0.0.1:3306/stock_greenfield_20261109",
                "",
                "");

        assertThatIllegalStateException()
                .isThrownBy(configured::validateConfiguration)
                .withMessageContaining("STOCK_DB_USER and STOCK_DB_PASSWORD");
    }

    @Test
    void rejectsJdbcUrlsThatNameAnotherSchema() {
        DatabaseTargetPolicy configured = new DatabaseTargetPolicy(
                "stock_greenfield_20261109",
                "jdbc:mysql://127.0.0.1:3306/stock",
                "scoped_user",
                "scoped_password");

        assertThatIllegalStateException()
                .isThrownBy(configured::validateConfiguration)
                .withMessageContaining("stock_greenfield_20261109");
    }

    @Test
    void rejectsUrlsThatCouldCreateTheSchemaImplicitly() {
        DatabaseTargetPolicy configured = new DatabaseTargetPolicy(
                "stock_greenfield_20261109",
                "jdbc:mysql://127.0.0.1:3306/stock_greenfield_20261109?createDatabaseIfNotExist=true",
                "scoped_user",
                "scoped_password");

        assertThatIllegalStateException()
                .isThrownBy(configured::validateConfiguration)
                .withMessageContaining("must not create databases");
    }
}
