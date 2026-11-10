package com.aquila.config;

public final class FlywayMigrationTargetPolicy {
    private final boolean databaseEnabled;
    private final String schema;
    private final String url;
    private final String username;
    private final String password;
    private final String runtimeUsername;
    private final String runtimePassword;

    public FlywayMigrationTargetPolicy(
            boolean databaseEnabled,
            String schema,
            String url,
            String username,
            String password,
            String runtimeUsername,
            String runtimePassword) {
        this.databaseEnabled = databaseEnabled;
        this.schema = schema;
        this.url = url;
        this.username = username;
        this.password = password;
        this.runtimeUsername = runtimeUsername;
        this.runtimePassword = runtimePassword;
    }

    public Target validate() {
        if (!databaseEnabled) {
            throw new IllegalStateException("Flyway requires the explicitly enabled greenfield database");
        }
        if (blank(runtimeUsername) || blank(runtimePassword)) {
            throw new IllegalStateException("Runtime database credentials must be explicit when Flyway is enabled");
        }
        if (blank(url)) {
            throw new IllegalStateException("STOCK_FLYWAY_URL must be explicit when Flyway is enabled");
        }
        if (blank(username)) {
            throw new IllegalStateException("STOCK_FLYWAY_USER must be explicit when Flyway is enabled");
        }
        if (blank(password)) {
            throw new IllegalStateException("STOCK_FLYWAY_PASSWORD must be explicit when Flyway is enabled");
        }
        DatabaseTargetPolicy.validateTargetUrl(schema, url);
        if (username.trim().equalsIgnoreCase(runtimeUsername.trim()) || password.equals(runtimePassword)) {
            throw new IllegalStateException(
                    "Flyway migration credentials must be distinct from runtime database credentials");
        }
        return new Target(schema, url, username, password);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Target(String schema, String url, String username, String password) {
        @Override
        public String toString() {
            return "Target[schema=" + schema + ", credentials=REDACTED]";
        }
    }
}
