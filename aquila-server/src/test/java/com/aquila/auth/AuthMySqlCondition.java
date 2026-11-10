package com.aquila.auth;

import com.aquila.config.DatabaseTargetPolicy;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

final class AuthMySqlCondition implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (!"true".equals(System.getProperty("mysql.it"))) {
            return ConditionEvaluationResult.disabled("MySQL integration profile is not enabled");
        }

        String schema = System.getenv("STOCK_MYSQL_IT_SCHEMA");
        String url = System.getenv("STOCK_MYSQL_IT_URL");
        String user = System.getenv("STOCK_MYSQL_IT_USER");
        String password = System.getenv("STOCK_MYSQL_IT_PASSWORD");
        String encryptionKey = System.getenv("STOCK_TOTP_ENCRYPTION_KEY");
        if (!DatabaseTargetPolicy.GREENFIELD_SCHEMA.equals(schema)
                || blank(url) || blank(user) || blank(password) || blank(encryptionKey)) {
            return ConditionEvaluationResult.disabled(
                    "MySQL auth acceptance requires explicit scoped credentials for "
                            + DatabaseTargetPolicy.GREENFIELD_SCHEMA
                            + " and STOCK_TOTP_ENCRYPTION_KEY");
        }

        try {
            new DatabaseTargetPolicy(schema, url, user, password).validateConfiguration();
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
