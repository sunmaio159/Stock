package com.aquila.auth;

import com.aquila.auth.SessionService.AdminSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class BootstrapApplicationService {
    static final Duration BOOTSTRAP_LIFETIME = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final PasswordHashService passwords;
    private final TotpService totp;
    private final TotpSecretCipher secretCipher;
    private final SessionService sessions;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public BootstrapApplicationService(
            JdbcTemplate jdbc,
            PasswordHashService passwords,
            TotpService totp,
            TotpSecretCipher secretCipher,
            SessionService sessions,
            TransactionTemplate transactions,
            Clock clock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.totp = totp;
        this.secretCipher = secretCipher;
        this.sessions = sessions;
        this.transactions = transactions;
        this.clock = clock;
    }

    public BootstrapMaterial createAdministrator(String username, char[] password) {
        String normalizedUsername = normalizeUsername(username);
        char[] passwordCopy = password == null ? null : password.clone();
        String passwordHash;
        try {
            passwordHash = passwords.hash(passwordCopy);
        } finally {
            if (passwordCopy != null) {
                java.util.Arrays.fill(passwordCopy, '\0');
            }
        }

        String userId = UUID.randomUUID().toString();
        String token = randomToken();
        String secret = totp.generateSecret();
        byte[] encryptedSecret = secretCipher.encrypt(secret);
        Instant now = clock.instant();
        try {
            transactions.executeWithoutResult(status -> {
                Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class);
                if (userCount != null && userCount > 0) {
                    throw new AuthException(HttpStatus.CONFLICT, 409, "Administrator is already initialized");
                }
                jdbc.update(
                        "INSERT INTO sys_user (user_id, singleton_key, username, password_hash, "
                                + "totp_secret_encrypted, enabled, created_at, updated_at) "
                                + "VALUES (?, 1, ?, ?, ?, FALSE, ?, ?)",
                        userId,
                        normalizedUsername,
                        passwordHash,
                        encryptedSecret,
                        Timestamp.from(now),
                        Timestamp.from(now));
                jdbc.update(
                        "INSERT INTO sys_bootstrap_context "
                                + "(context_id, user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID().toString(),
                        userId,
                        digest(token),
                        Timestamp.from(now.plus(BOOTSTRAP_LIFETIME)),
                        Timestamp.from(now));
                jdbc.update(
                        "INSERT INTO sys_audit_log (log_id, actor_user_id, action, metadata_json, created_at) "
                                + "VALUES (?, ?, 'ADMINISTRATOR_BOOTSTRAP_ISSUED', JSON_OBJECT(), ?)",
                        UUID.randomUUID().toString(),
                        userId,
                        Timestamp.from(now));
            });
        } catch (DuplicateKeyException exception) {
            throw new AuthException(HttpStatus.CONFLICT, 409, "Administrator is already initialized");
        } finally {
            java.util.Arrays.fill(encryptedSecret, (byte) 0);
        }
        return new BootstrapMaterial(token, secret, totp.otpauthUri(normalizedUsername, secret));
    }

    public BootstrapMaterial resetAdministrator(String username, char[] password) {
        String normalizedUsername = normalizeUsername(username);
        char[] passwordCopy = password == null ? null : password.clone();
        String passwordHash;
        try {
            passwordHash = passwords.hash(passwordCopy);
        } finally {
            if (passwordCopy != null) {
                java.util.Arrays.fill(passwordCopy, '\0');
            }
        }

        String token = randomToken();
        String secret = totp.generateSecret();
        byte[] encryptedSecret = secretCipher.encrypt(secret);
        Instant now = clock.instant();
        try {
            transactions.executeWithoutResult(status -> {
                String userId = jdbc.query(
                        "SELECT user_id FROM sys_user WHERE singleton_key = 1 FOR UPDATE",
                        result -> result.next() ? result.getString(1) : null);
                if (userId == null) {
                    throw new AuthException(HttpStatus.NOT_FOUND, 404, "Administrator is not initialized");
                }
                jdbc.update(
                        "UPDATE sys_user SET username = ?, password_hash = ?, totp_secret_encrypted = ?, "
                                + "totp_pending_secret_encrypted = NULL, totp_pending_expires_at = NULL, "
                                + "totp_last_step = NULL, enabled = FALSE, updated_at = ? WHERE user_id = ?",
                        normalizedUsername,
                        passwordHash,
                        encryptedSecret,
                        Timestamp.from(now),
                        userId);
                jdbc.update(
                        "UPDATE sys_user_session SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                        Timestamp.from(now),
                        userId);
                jdbc.update(
                        "UPDATE sys_recovery_code_batch SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                        Timestamp.from(now),
                        userId);
                jdbc.update(
                        "UPDATE sys_recovery_code SET revoked_at = ? WHERE used_at IS NULL AND revoked_at IS NULL "
                                + "AND batch_id IN (SELECT batch_id FROM sys_recovery_code_batch WHERE user_id = ?)",
                        Timestamp.from(now),
                        userId);
                jdbc.update(
                        "UPDATE sys_bootstrap_context SET consumed_at = ? "
                                + "WHERE user_id = ? AND consumed_at IS NULL",
                        Timestamp.from(now),
                        userId);
                jdbc.update(
                        "UPDATE sys_reauth_context SET consumed_at = ? "
                                + "WHERE user_id = ? AND consumed_at IS NULL",
                        Timestamp.from(now),
                        userId);
                jdbc.update(
                        "DELETE FROM sys_csrf_context WHERE session_id IN "
                                + "(SELECT session_id FROM sys_user_session WHERE user_id = ?)",
                        userId);
                jdbc.update(
                        "INSERT INTO sys_bootstrap_context "
                                + "(context_id, user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID().toString(),
                        userId,
                        digest(token),
                        Timestamp.from(now.plus(BOOTSTRAP_LIFETIME)),
                        Timestamp.from(now));
                jdbc.update(
                        "INSERT INTO sys_audit_log (log_id, actor_user_id, action, metadata_json, created_at) "
                                + "VALUES (?, ?, 'LOCAL_ADMIN_RESET', JSON_OBJECT(), ?)",
                        UUID.randomUUID().toString(),
                        userId,
                        Timestamp.from(now));
            });
        } catch (DuplicateKeyException exception) {
            throw new AuthException(HttpStatus.CONFLICT, 409, "Administrator name is already in use");
        } finally {
            java.util.Arrays.fill(encryptedSecret, (byte) 0);
        }
        return new BootstrapMaterial(token, secret, totp.otpauthUri(normalizedUsername, secret));
    }

    public AdminSession confirm(BootstrapConfirmation confirmation) {
        if (confirmation == null || blank(confirmation.bootstrapToken()) || blank(confirmation.totpCode())) {
            throw invalidBootstrap();
        }
        byte[] tokenHash = digest(confirmation.bootstrapToken());
        Instant now = clock.instant();
        return transactions.execute(status -> {
            BootstrapRow row = jdbc.query(
                    "SELECT c.context_id, c.user_id, c.expires_at, c.consumed_at, "
                            + "u.username, u.totp_secret_encrypted, u.totp_last_step "
                            + "FROM sys_bootstrap_context c JOIN sys_user u ON u.user_id = c.user_id "
                            + "WHERE c.token_hash = ? FOR UPDATE",
                    result -> result.next()
                            ? new BootstrapRow(
                                    result.getString("context_id"),
                                    result.getString("user_id"),
                                    result.getTimestamp("expires_at").toInstant(),
                                    result.getTimestamp("consumed_at"),
                                    result.getString("username"),
                                    result.getBytes("totp_secret_encrypted"),
                                    (Long) result.getObject("totp_last_step"))
                            : null,
                    tokenHash);
            if (row == null || row.consumedAt() != null || !row.expiresAt().isAfter(now)) {
                throw invalidBootstrap();
            }
            String secret = secretCipher.decrypt(row.encryptedTotpSecret());
            long lastStep = row.lastUsedStep() == null ? Long.MIN_VALUE : row.lastUsedStep();
            var matchedStep = totp.matchingStep(secret, confirmation.totpCode(), now, lastStep);
            if (matchedStep.isEmpty()) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, 401, "Invalid authentication code");
            }
            int consumed = jdbc.update(
                    "UPDATE sys_bootstrap_context SET consumed_at = ? "
                            + "WHERE context_id = ? AND consumed_at IS NULL AND expires_at > ?",
                    Timestamp.from(now),
                    row.contextId(),
                    Timestamp.from(now));
            int enabled = jdbc.update(
                    "UPDATE sys_user SET enabled = TRUE, totp_last_step = ?, updated_at = ? "
                            + "WHERE user_id = ? AND enabled = FALSE",
                    matchedStep.getAsLong(),
                    Timestamp.from(now),
                    row.userId());
            if (consumed != 1 || enabled != 1) {
                throw invalidBootstrap();
            }
            List<String> recoveryCodes = sessions.createRecoveryCodeBatch(row.userId(), now);
            AdminSession session = sessions.createSession(row.userId(), row.username(), "ADMIN", recoveryCodes, now);
            jdbc.update(
                    "INSERT INTO sys_audit_log (log_id, actor_user_id, action, metadata_json, created_at) "
                            + "VALUES (?, ?, 'ADMINISTRATOR_BOOTSTRAP_CONFIRMED', JSON_OBJECT(), ?)",
                    UUID.randomUUID().toString(),
                    row.userId(),
                    Timestamp.from(now));
            return session;
        });
    }

    public boolean isBootstrapTokenUsable(String token) {
        if (blank(token)) {
            return false;
        }
        Integer matches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_bootstrap_context "
                        + "WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ?",
                Integer.class,
                digest(token),
                Timestamp.from(clock.instant()));
        return matches != null && matches == 1;
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank() || username.length() > 80) {
            throw new AuthException(HttpStatus.BAD_REQUEST, 400, "Invalid administrator name");
        }
        return username.trim();
    }

    private String randomToken() {
        byte[] token = new byte[32];
        random.nextBytes(token);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } finally {
            java.util.Arrays.fill(token, (byte) 0);
        }
    }

    static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static AuthException invalidBootstrap() {
        return new AuthException(HttpStatus.UNAUTHORIZED, 401, "Invalid or expired bootstrap context");
    }

    private record BootstrapRow(
            String contextId,
            String userId,
            Instant expiresAt,
            Timestamp consumedAt,
            String username,
            byte[] encryptedTotpSecret,
            Long lastUsedStep) {}

    public record BootstrapMaterial(String bootstrapToken, String totpSecret, String otpauthUri) {
        @Override
        public String toString() {
            return "BootstrapMaterial[credentials=REDACTED]";
        }
    }

    public record BootstrapConfirmation(String bootstrapToken, String totpCode) {
        @Override
        public String toString() {
            return "BootstrapConfirmation[credentials=REDACTED]";
        }
    }
}
