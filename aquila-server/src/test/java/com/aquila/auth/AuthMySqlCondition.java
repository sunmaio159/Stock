package com.aquila.auth;

import com.aquila.config.DatabaseTargetPolicy;
import com.aquila.config.FlywayMigrationTargetPolicy;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class AuthMySqlCondition implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (!"true".equals(System.getProperty("mysql.it"))) {
            return ConditionEvaluationResult.disabled("MySQL integration profile is not enabled");
        }

        String schema = System.getenv("STOCK_MYSQL_IT_SCHEMA");
        String url = System.getenv("STOCK_MYSQL_IT_URL");
        String user = System.getenv("STOCK_MYSQL_IT_USER");
        String password = System.getenv("STOCK_MYSQL_IT_PASSWORD");
        String flywayUrl = System.getenv("STOCK_MYSQL_IT_FLYWAY_URL");
        String flywayUser = System.getenv("STOCK_MYSQL_IT_FLYWAY_USER");
        String flywayPassword = System.getenv("STOCK_MYSQL_IT_FLYWAY_PASSWORD");
        String encryptionKey = System.getenv("STOCK_TOTP_ENCRYPTION_KEY");
        if (!DatabaseTargetPolicy.GREENFIELD_SCHEMA.equals(schema)
                || blank(url) || blank(user) || blank(password)
                || blank(flywayUrl) || blank(flywayUser) || blank(flywayPassword)
                || blank(encryptionKey)) {
            return ConditionEvaluationResult.disabled(
                    "MySQL auth acceptance requires explicit scoped credentials for "
                            + DatabaseTargetPolicy.GREENFIELD_SCHEMA
                            + ", separate Flyway migration credentials, and STOCK_TOTP_ENCRYPTION_KEY");
        }

        try {
            new DatabaseTargetPolicy(schema, url, user, password).validateConfiguration();
            new FlywayMigrationTargetPolicy(
                            true, schema, flywayUrl, flywayUser, flywayPassword, user, password)
                    .validate();
        } catch (IllegalStateException exception) {
            return ConditionEvaluationResult.disabled("MySQL auth target guard rejected the configured target");
        }
        return ConditionEvaluationResult.enabled(
                "Explicit scoped MySQL target validated for " + DatabaseTargetPolicy.GREENFIELD_SCHEMA);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
