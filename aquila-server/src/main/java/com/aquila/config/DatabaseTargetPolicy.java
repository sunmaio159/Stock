package com.aquila.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DatabaseTargetPolicy {
    public static final String GREENFIELD_SCHEMA = "stock_greenfield_20261109";

    private final String schemaName;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public DatabaseTargetPolicy() {
        this(GREENFIELD_SCHEMA, "", "", "");
    }

    @Autowired
    public DatabaseTargetPolicy(
            @Value("${aquila.database.schema:stock_greenfield_20261109}") String schemaName,
            @Value("${aquila.database.url:}") String jdbcUrl,
            @Value("${aquila.database.username:}") String username,
            @Value("${aquila.database.password:}") String password) {
        this.schemaName = schemaName;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public boolean permits(String schemaName) {
        return GREENFIELD_SCHEMA.equals(schemaName);
    }

    @PostConstruct
    public void validateConfiguration() {
        if (!permits(schemaName)) {
            throw new IllegalStateException("Only schema " + GREENFIELD_SCHEMA + " is permitted");
        }
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return;
        }
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Database connection requires STOCK_DB_USER and STOCK_DB_PASSWORD");
        }

        validateTargetUrl(schemaName, jdbcUrl);
    }

    public static void validateTargetUrl(String schemaName, String jdbcUrl) {
        if (!GREENFIELD_SCHEMA.equals(schemaName)) {
            throw new IllegalStateException("Only schema " + GREENFIELD_SCHEMA + " is permitted");
        }
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("Database URL must name only " + GREENFIELD_SCHEMA);
        }

        URI target;
        try {
            if (!jdbcUrl.startsWith("jdbc:mysql://")) {
                throw new IllegalArgumentException("Only MySQL JDBC URLs are permitted");
            }
            target = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Database URL must name only " + GREENFIELD_SCHEMA, exception);
        }

        if (!"mysql".equals(target.getScheme())
                || target.getHost() == null
                || target.getRawUserInfo() != null
                || !("/" + GREENFIELD_SCHEMA).equals(target.getRawPath())) {
            throw new IllegalStateException("Database URL must name only " + GREENFIELD_SCHEMA);
        }

        String query = target.getRawQuery();
        if (query != null
                && URLDecoder.decode(query, StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT)
                        .contains("createdatabaseifnotexist=true")) {
            throw new IllegalStateException("Database URL must not create databases implicitly");
        }
    }
}
