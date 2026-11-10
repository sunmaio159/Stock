package com.aquila.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aquila.auth.BootstrapApplicationService.BootstrapConfirmation;
import com.aquila.auth.BootstrapApplicationService.BootstrapMaterial;
import com.aquila.auth.SessionService.LoginRequest;
import com.aquila.auth.SessionService.ReauthRequest;
import com.aquila.auth.SessionService.RecoveryLoginRequest;
import com.aquila.config.DatabaseTargetPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(AuthMySqlCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthFlowIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BootstrapApplicationService bootstrap;
    @Autowired private SessionService sessions;
    @Autowired private TotpService totp;

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
        properties.add("spring.flyway.url", () -> System.getenv("STOCK_MYSQL_IT_FLYWAY_URL"));
        properties.add("spring.flyway.user", () -> System.getenv("STOCK_MYSQL_IT_FLYWAY_USER"));
        properties.add("spring.flyway.password", () -> System.getenv("STOCK_MYSQL_IT_FLYWAY_PASSWORD"));
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
        jdbc.update("DELETE FROM sys_user_session");
        jdbc.update("DELETE FROM sys_bootstrap_context");
        jdbc.update("DELETE FROM sys_user");
    }

    @Test
    void bootstrapConfirmationIsSingleUseUnderConcurrencyAndCreatesTenRecoveryCodes() throws Exception {
        BootstrapMaterial material = bootstrap.createAdministrator(
                "stage-b-test", "strong-test-password".toCharArray());
        String confirmationCode = totp.generateCode(material.totpSecret(), Instant.now());
        AtomicReference<SessionService.AdminSession> winner = new AtomicReference<>();
        var pool = Executors.newFixedThreadPool(2);
        List<Boolean> results;
        try {
            List<Callable<Boolean>> attempts = List.of(
                    () -> tryBootstrapConfirm(material, confirmationCode, winner),
                    () -> tryBootstrapConfirm(material, confirmationCode, winner));
            results = new ArrayList<>();
            for (var result : pool.invokeAll(attempts)) {
                results.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(winner.get().recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_recovery_code WHERE used_at IS NULL AND revoked_at IS NULL",
                Integer.class)).isEqualTo(10);
        assertThatThrownBy(() -> bootstrap.confirm(
                new BootstrapConfirmation(material.bootstrapToken(), confirmationCode)))
                .isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(1);
        byte[] encryptedSecret = jdbc.queryForObject(
                "SELECT totp_secret_encrypted FROM sys_user WHERE username = ?",
                byte[].class,
                "stage-b-test");
        assertThat(encryptedSecret).isNotEmpty();
        assertThat(new String(encryptedSecret)).doesNotContain(material.totpSecret());
    }

    @Test
    void aTotpStepCannotAuthenticateTwice() {
        Initialized initialized = createEnabledAdministrator("totp-test");
        String consumedAtBootstrap = totp.generateCode(initialized.material().totpSecret(), Instant.now());

        assertThatThrownBy(() -> sessions.login(
                new LoginRequest("totp-test", "strong-test-password", consumedAtBootstrap)))
                .isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_session", Integer.class)).isEqualTo(0);
    }

    @Test
    void replacementTotpMayConfirmInTheStepConsumedByThePreviousSecret() {
        BootstrapMaterial material = bootstrap.createAdministrator(
                "totp-replacement-test", "strong-test-password".toCharArray());
        var session = bootstrap.confirm(new BootstrapConfirmation(
                material.bootstrapToken(),
                totp.generateCode(material.totpSecret(), Instant.now())));
        Instant sameStep = Instant.now();
        var reauth = sessions.reauthenticate(
                session.token(),
                new ReauthRequest("strong-test-password", totp.generateCode(material.totpSecret(), sameStep)));
        var replacement = sessions.beginTotpSetup(session.token(), reauth.token());

        var codes = sessions.confirmTotpSetup(
                session.token(),
                reauth.token(),
                totp.generateCode(replacement.secret(), sameStep));

        assertThat(codes).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    void concurrentLoginsCannotConsumeTheSameTotpStep() throws Exception {
        Initialized initialized = createEnabledAdministrator("totp-race-test");
        String code = totp.generateCode(initialized.material().totpSecret(), Instant.now().plusSeconds(30));
        var pool = Executors.newFixedThreadPool(2);
        List<Boolean> results;
        try {
            List<Callable<Boolean>> attempts = List.of(
                    () -> tryLogin("totp-race-test", code),
                    () -> tryLogin("totp-race-test", code));
            results = new ArrayList<>();
            for (var result : pool.invokeAll(attempts)) {
                results.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_session", Integer.class)).isEqualTo(2);
    }

    @Test
    void aRecoveryCodeCanBeConsumedByOnlyOneConcurrentRequest() throws Exception {
        Initialized initialized = createEnabledAdministrator("recovery-test");
        String recoveryCode = initialized.session().recoveryCodes().get(0);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> attempts = List.of(
                    () -> tryRecoveryLogin("recovery-test", recoveryCode),
                    () -> tryRecoveryLogin("recovery-test", recoveryCode));
            List<Boolean> results = new ArrayList<>();
            for (var result : pool.invokeAll(attempts)) {
                results.add(result.get());
            }
            assertThat(results).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_session WHERE purpose = 'RECOVERY'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void loginRejectsTheEleventhFailureUntilOldFailuresAgeOut() {
        createEnabledAdministrator("rate-limit-test");
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThatThrownBy(() -> sessions.login(
                    new LoginRequest("rate-limit-test", "incorrect-password", "000000")))
                    .isInstanceOf(AuthException.class);
        }

        assertThatThrownBy(() -> sessions.login(
                new LoginRequest("rate-limit-test", "strong-test-password", "000000")))
                .isInstanceOf(RateLimitedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_login_failure", Integer.class)).isEqualTo(10);
    }

    @Test
    void localResetRevokesSessionsAndRecoveryCodesBeforeRebinding() {
        BootstrapMaterial original = bootstrap.createAdministrator(
                "before-reset", "strong-test-password".toCharArray());
        var oldSession = bootstrap.confirm(new BootstrapConfirmation(
                original.bootstrapToken(),
                totp.generateCode(original.totpSecret(), Instant.now())));

        BootstrapMaterial reset = bootstrap.resetAdministrator("after-reset", "new-strong-password".toCharArray());

        assertThat(sessions.findActiveSession(oldSession.token())).isEmpty();
        assertThat(bootstrap.isBootstrapTokenUsable(original.bootstrapToken())).isFalse();
        assertThat(bootstrap.isBootstrapTokenUsable(reset.bootstrapToken())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_recovery_code_batch WHERE revoked_at IS NOT NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT enabled FROM sys_user", Boolean.class)).isFalse();

        var rebound = bootstrap.confirm(new BootstrapConfirmation(
                reset.bootstrapToken(),
                totp.generateCode(reset.totpSecret(), Instant.now())));
        assertThat(rebound.username()).isEqualTo("after-reset");
        assertThat(rebound.recoveryCodes()).hasSize(10);
    }

    private Initialized createEnabledAdministrator(String username) {
        BootstrapMaterial material = bootstrap.createAdministrator(username, "strong-test-password".toCharArray());
        String code = totp.generateCode(material.totpSecret(), Instant.now());
        var session = bootstrap.confirm(new BootstrapConfirmation(material.bootstrapToken(), code));
        return new Initialized(material, session);
    }

    private boolean tryRecoveryLogin(String username, String recoveryCode) {
        try {
            sessions.recoveryLogin(new RecoveryLoginRequest(username, "strong-test-password", recoveryCode));
            return true;
        } catch (AuthException exception) {
            return false;
        }
    }

    private boolean tryBootstrapConfirm(
            BootstrapMaterial material,
            String code,
            AtomicReference<SessionService.AdminSession> winner) {
        try {
            winner.set(bootstrap.confirm(new BootstrapConfirmation(material.bootstrapToken(), code)));
            return true;
        } catch (AuthException exception) {
            return false;
        }
    }

    private boolean tryLogin(String username, String code) {
        try {
            sessions.login(new LoginRequest(username, "strong-test-password", code));
            return true;
        } catch (AuthException exception) {
            return false;
        }
    }

    private record Initialized(BootstrapMaterial material, SessionService.AdminSession session) {}
}
