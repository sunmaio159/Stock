package com.aquila.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayMigrationConfiguration {
    @Bean
    @ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true")
    public FlywayConfigurationCustomizer flywayMigrationCustomizer(
            @Value("${aquila.database.enabled:false}") boolean databaseEnabled,
            @Value("${aquila.database.schema:}") String schema,
            @Value("${spring.flyway.url:}") String url,
            @Value("${spring.flyway.user:}") String username,
            @Value("${spring.flyway.password:}") String password,
            @Value("${aquila.database.username:}") String runtimeUsername,
            @Value("${aquila.database.password:}") String runtimePassword) {
        return flywayMigrationCustomizer(new FlywayMigrationTargetPolicy(
                databaseEnabled, schema, url, username, password, runtimeUsername, runtimePassword));
    }

    FlywayConfigurationCustomizer flywayMigrationCustomizer(FlywayMigrationTargetPolicy policy) {
        FlywayMigrationTargetPolicy.Target target = policy.validate();
        return (FluentConfiguration configuration) -> configuration
                .dataSource(target.url(), target.username(), target.password())
                .schemas(target.schema())
                .defaultSchema(target.schema());
    }
}
