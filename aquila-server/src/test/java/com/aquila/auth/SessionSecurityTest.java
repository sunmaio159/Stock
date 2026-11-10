package com.aquila.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aquila.auth.BootstrapApplicationService.BootstrapConfirmation;
import com.aquila.auth.BootstrapApplicationService.BootstrapMaterial;
import com.aquila.auth.SessionService.LoginRequest;
import com.aquila.config.DatabaseTargetPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest
@AutoConfigureMockMvc
class SessionSecurityTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BootstrapApplicationService bootstrap;
    @Autowired private SessionService sessions;
    @Autowired private PasswordHashService passwords;
    @Autowired private TotpService totp;
    @Autowired private RecoveryCodeService recoveryCodes;
    @Autowired private TotpSecretCipher secretCipher;
    @Autowired private TransactionTemplate transactions;
    @Autowired private java.time.Clock clock;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("aquila.database.enabled", () -> "true");
        properties.add("spring.datasource.url", () -> System.getenv("STOCK_MYSQL_IT_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("STOCK_MYSQL_IT_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("STOCK_MYSQL_IT_PASSWORD"));
        properties.add("aquila.database.schema", () -> DatabaseTargetPolicy.GREENFIELD_SCHEMA);
        properties.add("aquila.database.url", () -> System.getenv("STOCK_MYSQL_IT_URL"));
        properties.add("aquila.database.username", () -> System.getenv("STOCK_MYSQL_IT_USER"));
        properties.add("aquila.database.password", () -> System.getenv("STOCK_MYSQL_IT_PASSWORD"));
        properties.add("aquila.security.allowed-origin", () -> "http://localhost:8080");
        properties.add("aquila.security.totp-encryption-key", () -> System.getenv("STOCK_TOTP_ENCRYPTION_KEY"));
        properties.add("spring.flyway.enabled", () -> "true");
    }

    @BeforeEach
    void clearIdentityState() {
        jdbc.update("DELETE FROM sys_audit_log");
        jdbc.update("DELETE FROM sys_login_failure");
        jdbc.update("DELETE FROM sys_recovery_code");
        jdbc.update("DELETE FROM sys_recovery_code_batch");
        jdbc.update("DELETE FROM sys_reauth_context");
        jdbc.update("DELETE FROM sys_csrf_context");
        jdbc.update("DELETE FROM sys_user_session");
        jdbc.update("DELETE FROM sys_bootstrap_context");
        jdbc.update("DELETE FROM sys_user");
    }

    @Test
    void csrfContextIsAvailableBeforeLoginAndProtectsTheLoginRequest() throws Exception {
        Csrf credentials = csrf();
        String invalidLogin = """
                {"username":"not-yet-created","password":"invalid","totpCode":"000000"}
                """;

        mvc.perform(post("/api/auth/login")
                        .cookie(credentials.cookie())
                        .header("X-CSRF-Token", credentials.token())
                        .contentType("application/json")
                        .content(invalidLogin))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/login")
                        .cookie(credentials.cookie())
                        .header("X-CSRF-Token", credentials.token())
                        .header("Origin", "https://attacker.invalid")
                        .contentType("application/json")
                        .content(invalidLogin))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/login")
                        .cookie(credentials.cookie())
                        .header("X-CSRF-Token", credentials.token())
                        .header("Origin", "http://localhost:8080")
                        .contentType("application/json")
                        .content(invalidLogin))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginUsesTheApprovedPersistentHttpOnlyCookieWithoutExposingItsToken() throws Exception {
        BootstrapMaterial material = bootstrap.createAdministrator(
                "cookie-test", "strong-test-password".toCharArray());
        bootstrap.confirm(new BootstrapConfirmation(
                material.bootstrapToken(),
                totp.generateCode(material.totpSecret(), Instant.now())));
        Csrf credentials = csrf();
        String loginCode = totp.generateCode(material.totpSecret(), Instant.now().plusSeconds(30));

        MvcResult result = mvc.perform(post("/api/auth/login")
                        .cookie(credentials.cookie())
                        .header("X-CSRF-Token", credentials.token())
                        .header("Origin", "http://localhost:8080")
                        .contentType("application/json")
                        .content("""
                                {"username":"cookie-test","password":"strong-test-password","totpCode":"%s"}
                                """.formatted(loginCode)))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("AQUILA_SESSION=", "HttpOnly", "SameSite=Strict", "Path=/",
                "Max-Age=31536000");
        assertThat(setCookie).doesNotContain("Domain=", "Secure");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("AQUILA_SESSION=");
    }

    @Test
    void sessionSurvivesServiceRecreationAndRevocationIsObservedImmediately() {
        BootstrapMaterial material = bootstrap.createAdministrator(
                "session-test", "strong-test-password".toCharArray());
        bootstrap.confirm(new BootstrapConfirmation(
                material.bootstrapToken(),
                totp.generateCode(material.totpSecret(), Instant.now())));
        String loginCode = totp.generateCode(material.totpSecret(), Instant.now().plusSeconds(30));
        var session = sessions.login(new LoginRequest("session-test", "strong-test-password", loginCode));

        SessionService restarted = new SessionService(
                jdbc, passwords, totp, recoveryCodes, secretCipher, clock, transactions);
        assertThat(restarted.findActiveSession(session.token())).isPresent();

        restarted.revoke(session.id());

        assertThat(sessions.findActiveSession(session.token())).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_session WHERE session_id = ? AND revoked_at IS NOT NULL",
                Integer.class,
                session.id())).isEqualTo(1);
    }

    @Test
    void rollingFailureLimitSurvivesServiceRecreation() {
        BootstrapMaterial material = bootstrap.createAdministrator(
                "rate-restart-test", "strong-test-password".toCharArray());
        bootstrap.confirm(new BootstrapConfirmation(
                material.bootstrapToken(),
                totp.generateCode(material.totpSecret(), Instant.now())));
        for (int attempt = 0; attempt < 10; attempt++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessions.login(
                    new LoginRequest("rate-restart-test", "wrong-password", "000000")))
                    .isInstanceOf(AuthException.class);
        }

        SessionService restarted = new SessionService(
                jdbc, passwords, totp, recoveryCodes, secretCipher, clock, transactions);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> restarted.login(
                new LoginRequest("rate-restart-test", "strong-test-password", "000000")))
                .isInstanceOf(RateLimitedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_login_failure", Integer.class)).isEqualTo(10);
    }

    private Csrf csrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        Cookie cookie = result.getResponse().getCookie("AQUILA_CSRF");
        assertThat(cookie).isNotNull();
        return new Csrf(cookie, envelope.path("data").path("token").asText());
    }

    private record Csrf(Cookie cookie, String token) {}
}
