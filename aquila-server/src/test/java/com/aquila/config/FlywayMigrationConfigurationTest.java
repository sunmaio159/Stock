package com.aquila.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

class FlywayMigrationConfigurationTest {
    private static final String SCHEMA = DatabaseTargetPolicy.GREENFIELD_SCHEMA;
    private static final String RUNTIME_URL = "jdbc:mysql://127.0.0.1:3306/" + SCHEMA;
    private static final String MIGRATION_URL = "jdbc:mysql://127.0.0.1:3306/" + SCHEMA;

    @Test
    void requiresAnExplicitMigrationUrl() {
        assertThatThrownBy(() -> policy("", "migration-user", "migration-password").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STOCK_FLYWAY_URL");
    }

    @Test
    void requiresAnExplicitMigrationUsernameAndPassword() {
        assertThatThrownBy(() -> policy(MIGRATION_URL, "", "migration-password").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STOCK_FLYWAY_USER");
        assertThatThrownBy(() -> policy(MIGRATION_URL, "migration-user", "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STOCK_FLYWAY_PASSWORD");
    }

    @Test
    void rejectsRuntimeCredentialsInsteadOfAllowingFlywayDatasourceFallback() {
        assertThatThrownBy(() -> policy(MIGRATION_URL, "runtime-user", "migration-password").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distinct from runtime database credentials");
        assertThatThrownBy(() -> policy(MIGRATION_URL, "migration-user", "runtime-password").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distinct from runtime database credentials");
        assertThatThrownBy(() -> new FlywayMigrationTargetPolicy(
                        true,
                        SCHEMA,
                        MIGRATION_URL,
                        "RUNTIME-USER",
                        "migration-password",
                        "runtime-user",
                        "runtime-password")
                        .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distinct from runtime database credentials");
    }

    @Test
    void requiresTheGreenfieldDatabaseToBeExplicitlyEnabled() {
        assertThatThrownBy(() -> new FlywayMigrationTargetPolicy(
                        false,
                        SCHEMA,
                        MIGRATION_URL,
                        "migration-user",
                        "migration-password",
                        "runtime-user",
                        "runtime-password")
                        .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicitly enabled greenfield database");
    }

    @Test
    void acceptsOnlyMigrationTargetsForTheExplicitGreenfieldSchema() {
        assertThatThrownBy(() -> new FlywayMigrationTargetPolicy(
                        true,
                        "stock",
                        MIGRATION_URL,
                        "migration-user",
                        "migration-password",
                        "runtime-user",
                        "runtime-password")
                        .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SCHEMA);
        assertThatThrownBy(() -> policy(
                        "jdbc:mysql://127.0.0.1:3306/stock",
                        "migration-user",
                        "migration-password")
                        .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SCHEMA);
    }

    @Test
    void configuresFlywayWithMigrationAccountAndRuntimePoolWithRuntimeAccount() {
        HikariConfig runtime = DatabaseConfiguration.runtimeDataSourceConfig(
                new DatabaseTargetPolicy(SCHEMA, RUNTIME_URL, "runtime-user", "runtime-password"),
                RUNTIME_URL,
                "runtime-user",
                "runtime-password");
        FluentConfiguration flyway = new FluentConfiguration();
        new FlywayMigrationConfiguration()
                .flywayMigrationCustomizer(policy(MIGRATION_URL, "migration-user", "migration-password"))
                .customize(flyway);

        assertThat(runtime.getJdbcUrl()).isEqualTo(RUNTIME_URL);
        assertThat(runtime.getUsername()).isEqualTo("runtime-user");
        assertThat(runtime.getPassword()).isEqualTo("runtime-password");
        assertThat(flyway.getUrl()).isEqualTo(MIGRATION_URL);
        assertThat(flyway.getUser()).isEqualTo("migration-user");
        assertThat(flyway.getPassword()).isEqualTo("migration-password");
        assertThat(flyway.getDataSource()).isNotNull().isNotInstanceOf(HikariDataSource.class);
    }

    private FlywayMigrationTargetPolicy policy(String migrationUrl, String migrationUser, String migrationPassword) {
        return new FlywayMigrationTargetPolicy(
                true,
                SCHEMA,
                migrationUrl,
                migrationUser,
                migrationPassword,
                "runtime-user",
                "runtime-password");
    }
}
