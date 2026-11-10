package com.aquila.config;

import com.aquila.auth.CsrfFilter;
import com.aquila.auth.PasswordHashService;
import com.aquila.auth.RecoveryCodeService;
import com.aquila.auth.SessionService;
import com.aquila.auth.TotpSecretCipher;
import com.aquila.auth.TotpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class SecurityConfiguration {
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    public PasswordHashService passwordHashService() {
        return new PasswordHashService();
    }

    @Bean
    public TotpService totpService() {
        return new TotpService();
    }

    @Bean
    public RecoveryCodeService recoveryCodeService() {
        return new RecoveryCodeService();
    }

    @Bean
    @ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
    public TotpSecretCipher totpSecretCipher(
            @Value("${aquila.security.totp-encryption-key:}") String key) {
        return new TotpSecretCipher(key);
    }

    @Bean
    @ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
    public CsrfFilter csrfFilter(
            JdbcTemplate jdbc,
            SessionService sessions,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${aquila.security.allowed-origin:}") String allowedOrigin) {
        return new CsrfFilter(jdbc, sessions, objectMapper, clock, allowedOrigin);
    }

    @Bean
    @ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
    public FilterRegistrationBean<CsrfFilter> csrfFilterRegistration(CsrfFilter filter) {
        FilterRegistrationBean<CsrfFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
