package com.aquila.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class DatabaseConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(
            DatabaseTargetPolicy targetPolicy,
            @Value("${aquila.database.url:}") String url,
            @Value("${aquila.database.username:}") String username,
            @Value("${aquila.database.password:}") String password) {
        targetPolicy.validateConfiguration();
        if (url.isBlank() || username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("An explicit greenfield MySQL URL and scoped credentials are required");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("aquila-greenfield");
        return new HikariDataSource(config);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
