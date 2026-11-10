package com.aquila.db;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.assertj.core.api.Assertions.assertThat;

import com.aquila.config.DatabaseTargetPolicy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class FlywayMySqlIntegrationTest {
    private static final String SCHEMA = "stock_greenfield_20261109";

    @Test
    @EnabledIfSystemProperty(named = "mysql.it", matches = "true")
    void explicitlyProvisionedSchemaIsEmptyMySql8AndMigrationFree() throws Exception {
        String url = System.getenv("STOCK_MYSQL_IT_URL");
        String schema = System.getenv("STOCK_MYSQL_IT_SCHEMA");
        String user = System.getenv("STOCK_MYSQL_IT_USER");
        String password = System.getenv("STOCK_MYSQL_IT_PASSWORD");

        if (url == null || url.isBlank()
                || schema == null || schema.isBlank()
                || user == null || user.isBlank()
                || password == null || password.isBlank()) {
            System.out.println("DB acceptance pending: provision the isolated " + SCHEMA
                    + " schema and set all STOCK_MYSQL_IT_* variables.");
            assumeTrue(false, "isolated MySQL schema is not provisioned");
        }

        new DatabaseTargetPolicy(schema, url, user, password).validateConfiguration();

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            assertThat(connection.getCatalog()).isEqualTo(SCHEMA);
            try (ResultSet version = statement.executeQuery("SELECT VERSION()")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getString(1)).startsWith("8.");
            }

            List<String> tables = new ArrayList<>();
            try (ResultSet results = statement.executeQuery("SHOW TABLES")) {
                while (results.next()) {
                    tables.add(results.getString(1));
                }
            }
            assertThat(tables).isEmpty();
            assertThat(tables).doesNotContain("flyway_schema_history");
        }
    }
}
