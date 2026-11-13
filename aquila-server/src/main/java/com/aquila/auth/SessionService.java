package com.aquila.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class SessionService {
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_FAILURES = 10;
    private static final Duration REAUTH_LIFETIME = Duration.ofMinutes(5);
    private static final Duration TOTP_SETUP_LIFETIME = Duration.ofMinutes(15);
    public static final Duration RECOVERY_CONTEXT_LIFETIME = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final PasswordHashService passwords;
    private final TotpService totp;
    private final RecoveryCodeService recoveryCodes;
    private final TotpSecretCipher secretCipher;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public SessionService(
            JdbcTemplate jdbc,
            PasswordHashService passwords,
            TotpService totp,
            RecoveryCodeService recoveryCodes,
            TotpSecretCipher secretCipher,
            Clock clock,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.totp = totp;
        this.recoveryCodes = recoveryCodes;
        this.secretCipher = secretCipher;
        this.clock = clock;
        this.transactions = transactions;
    }

    public AdminSession login(LoginRequest request) {
        if (request == null || blank(request.username()) || request.password() == null) {
            throw invalidCredentials();
        }
        AuthOutcome outcome = transactions.execute(status -> {
            AdminRow user = lockUser(request.username());
            if (user == null || !user.enabled()) {
                return AuthOutcome.failed(invalidCredentials());
            }
            Instant now = clock.instant();
            if (isLimited(user.userId(), now)) {
                writeAudit(user.userId(), "AUTH_LOGIN_RATE_LIMITED", now);
                return AuthOutcome.failed(new RateLimitedException());
            }
            if (!passwordMatches(request.password(), user.passwordHash())) {
                recordFailure(user.userId(), now);
                return AuthOutcome.failed(invalidCredentials());
            }
            String secret = secretCipher.decrypt(user.encryptedTotpSecret());
            var step = totp.matchingStep(secret, request.totpCode(), now, lastUsedStep(user.lastUsedStep()));
            if (step.isEmpty() || !consumeTotpStep(user.userId(), step.getAsLong(), now)) {
                recordFailure(user.userId(), now);
                return AuthOutcome.failed(invalidCredentials());
            }
            return AuthOutcome.success(createSession(user.userId(), user.username(), "ADMIN", List.of(), now));
        });
        return outcome.unwrap();
    }

    public AdminSession recoveryLogin(RecoveryLoginRequest request) {
        if (request == null || blank(request.username()) || request.password() == null || blank(request.recoveryCode())) {
            throw invalidCredentials();
        }
        AuthOutcome outcome = transactions.execute(status -> {
            AdminRow user = lockUser(request.username());
            if (user == null || !user.enabled()) {
                return AuthOutcome.failed(invalidCredentials());
            }
            Instant now = clock.instant();
            if (isLimited(user.userId(), now)) {
                writeAudit(user.userId(), "AUTH_LOGIN_RATE_LIMITED", now);
                return AuthOutcome.failed(new RateLimitedException());
            }
            if (!passwordMatches(request.password(), user.passwordHash())) {
                recordFailure(user.userId(), now);
                return AuthOutcome.failed(invalidCredentials());
            }
            byte[] codeHash;
            try {
                codeHash = recoveryCodes.hashCode(request.recoveryCode());
            } catch (IllegalArgumentException exception) {
                recordFailure(user.userId(), now);
                return AuthOutcome.failed(invalidCredentials());
            }
            int consumed = jdbc.update(
                    "UPDATE sys_recovery_code rc "
                            + "JOIN sys_recovery_code_batch b ON b.batch_id = rc.batch_id "
                            + "SET rc.used_at = ? "
                            + "WHERE rc.code_hash = ? AND rc.used_at IS NULL AND rc.revoked_at IS NULL "
                            + "AND b.user_id = ? AND b.revoked_at IS NULL",
                    Timestamp.from(now),
                    codeHash,
                    user.userId());
            java.util.Arrays.fill(codeHash, (byte) 0);
            if (consumed != 1) {
                recordFailure(user.userId(), now);
                return AuthOutcome.failed(invalidCredentials());
            }
            return AuthOutcome.success(createSession(user.userId(), user.username(), "RECOVERY", List.of(), now));
        });
        return outcome.unwrap();
    }

    public ReauthGrant reauthenticate(String sessionToken, ReauthRequest request) {
        SessionPrincipal principal = requireAdminSession(sessionToken);
        if (request == null || request.password() == null) {
            throw invalidCredentials();
        }
        AuthOutcome outcome = transactions.execute(status -> {
            AdminRow user = lockUserById(principal.userId());
            Instant now = clock.instant();
            if (isLimited(user.userId(), now)) {
                writeAudit(user.userId(), "AUTH_REAUTH_RATE_LIMITED", now);
                return AuthOutcome.failed(new RateLimitedException());
            }
            if (!passwordMatches(request.password(), user.passwordHash())) {
                recordFailure(user.userId(), now);
                return AuthOutcome.failed(invalidCredentials());
            }
            String secret = secretCipher.decrypt(user.encryptedTotpSecret());
            var step = totp.matchingStep(secret, request.totpCode(), now, lastUsedStep(user.lastUsedStep()));
            if (step.isEmpty() || !consumeTotpStep(user.userId(), step.getAsLong(), now)) {
                recordFailure(user.userId(), now);
                return AuthOutcome.failed(invalidCredentials());
            }
            String token = randomToken();
            Instant expiresAt = now.plus(REAUTH_LIFETIME);
            jdbc.update(
                    "INSERT INTO sys_reauth_context "
                            + "(context_id, user_id, session_id, token_hash, expires_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(),
                    user.userId(),
                    principal.sessionId(),
                    digest(token),
                    Timestamp.from(expiresAt),
                    Timestamp.from(now));
            return AuthOutcome.success(new ReauthSession(token, expiresAt));
        });
        Object value = outcome.unwrapValue();
        ReauthSession grant = (ReauthSession) value;
        return new ReauthGrant(grant.token(), grant.expiresAt());
    }

    public TotpEnrollment beginTotpSetup(String sessionToken, String reauthToken) {
        SessionPrincipal principal = requireAdminSession(sessionToken);
        requireReauth(principal, reauthToken, false);
        String secret = totp.generateSecret();
        byte[] encrypted = secretCipher.encrypt(secret);
        Instant now = clock.instant();
        jdbc.update(
                "UPDATE sys_user SET totp_pending_secret_encrypted = ?, totp_pending_expires_at = ?, updated_at = ? "
                        + "WHERE user_id = ? AND enabled = TRUE",
                encrypted,
                Timestamp.from(now.plus(TOTP_SETUP_LIFETIME)),
                Timestamp.from(now),
                principal.userId());
        java.util.Arrays.fill(encrypted, (byte) 0);
        return new TotpEnrollment(secret, totp.otpauthUri(principal.username(), secret));
    }

    public List<String> confirmTotpSetup(String sessionToken, String reauthToken, String code) {
        SessionPrincipal principal = requireAdminSession(sessionToken);
        return transactions.execute(status -> {
            ReauthRow reauth = lockReauth(principal, reauthToken);
            Instant now = clock.instant();
            UserPendingSecret pending = loadPendingSecret(principal.userId(), now);
            String secret = secretCipher.decrypt(pending.encryptedSecret());
            var step = totp.matchingStep(secret, code, now, Long.MIN_VALUE);
            if (step.isEmpty()) {
                throw invalidCredentials();
            }
            consumeReauth(reauth, now);
            int updated = jdbc.update(
                    "UPDATE sys_user SET totp_secret_encrypted = totp_pending_secret_encrypted, "
                            + "totp_pending_secret_encrypted = NULL, totp_pending_expires_at = NULL, "
                            + "totp_last_step = ?, updated_at = ? WHERE user_id = ?",
                    step.getAsLong(),
                    Timestamp.from(now),
                    principal.userId());
            if (updated != 1) {
                throw invalidCredentials();
            }
            jdbc.update(
                    "UPDATE sys_user_session SET revoked_at = ? "
                            + "WHERE user_id = ? AND session_id <> ? AND revoked_at IS NULL",
                    Timestamp.from(now),
                    principal.userId(),
                    principal.sessionId());
            revokeRecoveryCodes(principal.userId(), now);
            List<String> codes = createRecoveryCodeBatch(principal.userId(), now);
            writeAudit(principal.userId(), "TOTP_SECRET_ROTATED", now);
            return codes;
        });
    }

    public List<String> rotateRecoveryCodes(String sessionToken, String reauthToken) {
        SessionPrincipal principal = requireAdminSession(sessionToken);
        return transactions.execute(status -> {
            ReauthRow reauth = lockReauth(principal, reauthToken);
            Instant now = clock.instant();
            consumeReauth(reauth, now);
            revokeRecoveryCodes(principal.userId(), now);
            List<String> codes = createRecoveryCodeBatch(principal.userId(), now);
            writeAudit(principal.userId(), "RECOVERY_CODES_ROTATED", now);
            return codes;
        });
    }

    public TotpEnrollment beginRecoveryRebind(String recoverySessionToken) {
        SessionPrincipal principal = requirePurpose(recoverySessionToken, "RECOVERY");
        String secret = totp.generateSecret();
        byte[] encrypted = secretCipher.encrypt(secret);
        Instant now = clock.instant();
        jdbc.update(
                "UPDATE sys_user SET totp_pending_secret_encrypted = ?, totp_pending_expires_at = ?, updated_at = ? "
                        + "WHERE user_id = ? AND enabled = TRUE",
                encrypted,
                Timestamp.from(now.plus(TOTP_SETUP_LIFETIME)),
                Timestamp.from(now),
                principal.userId());
        java.util.Arrays.fill(encrypted, (byte) 0);
        return new TotpEnrollment(secret, totp.otpauthUri(principal.username(), secret));
    }

    public List<String> confirmRecoveryRebind(String recoverySessionToken, String code) {
        SessionPrincipal principal = requirePurpose(recoverySessionToken, "RECOVERY");
        return transactions.execute(status -> {
            Instant now = clock.instant();
            UserPendingSecret pending = loadPendingSecret(principal.userId(), now);
            String secret = secretCipher.decrypt(pending.encryptedSecret());
            var step = totp.matchingStep(secret, code, now, Long.MIN_VALUE);
            if (step.isEmpty()) {
                throw invalidCredentials();
            }
            int updated = jdbc.update(
                    "UPDATE sys_user SET totp_secret_encrypted = totp_pending_secret_encrypted, "
                            + "totp_pending_secret_encrypted = NULL, totp_pending_expires_at = NULL, "
                            + "totp_last_step = ?, updated_at = ? WHERE user_id = ?",
                    step.getAsLong(),
                    Timestamp.from(now),
                    principal.userId());
            if (updated != 1) {
                throw invalidCredentials();
            }
            jdbc.update(
                    "UPDATE sys_user_session SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                    Timestamp.from(now),
                    principal.userId());
            revokeRecoveryCodes(principal.userId(), now);
            List<String> codes = createRecoveryCodeBatch(principal.userId(), now);
            writeAudit(principal.userId(), "RECOVERY_TOTP_REBOUND", now);
            return codes;
        });
    }

    public void revoke(String sessionId) {
        Instant now = clock.instant();
        int changed = jdbc.update(
                "UPDATE sys_user_session SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL",
                Timestamp.from(now),
                sessionId);
        if (changed > 0) {
            jdbc.update(
                    "INSERT INTO sys_audit_log (log_id, actor_user_id, action, metadata_json, created_at) "
                            + "SELECT ?, user_id, 'SESSION_REVOKED', JSON_OBJECT(), ? "
                            + "FROM sys_user_session WHERE session_id = ?",
                    UUID.randomUUID().toString(),
                    Timestamp.from(now),
                    sessionId);
        }
    }

    public List<SessionSummary> listSessions(String sessionToken) {
        SessionPrincipal principal = requireAdminSession(sessionToken);
        return jdbc.query(
                "SELECT session_id, purpose, revoked_at FROM sys_user_session "
                        + "WHERE user_id = ? ORDER BY session_id",
                (result, rowNum) -> new SessionSummary(
                        result.getString("session_id"),
                        result.getString("purpose"),
                        principal.sessionId().equals(result.getString("session_id")),
                        result.getTimestamp("revoked_at") != null),
                principal.userId());
    }

    public boolean revokeOwned(String sessionToken, String sessionId) {
        SessionPrincipal principal = requireAdminSession(sessionToken);
        Instant now = clock.instant();
        int changed = jdbc.update(
                "UPDATE sys_user_session SET revoked_at = ? "
                        + "WHERE session_id = ? AND user_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), sessionId, principal.userId());
        if (changed > 0) {
            jdbc.update(
                    "INSERT INTO sys_audit_log (log_id, actor_user_id, action, metadata_json, created_at) "
                            + "SELECT ?, user_id, 'SESSION_REVOKED', JSON_OBJECT(), ? "
                            + "FROM sys_user_session WHERE session_id = ?",
                    UUID.randomUUID().toString(),
                    Timestamp.from(now),
                    sessionId);
        }
        return changed > 0;
    }

    public Optional<SessionPrincipal> findActiveSession(String token) {
        if (blank(token)) {
            return Optional.empty();
        }
        List<SessionPrincipal> matches = jdbc.query(
                "SELECT s.session_id, s.user_id, u.username, s.purpose "
                        + "FROM sys_user_session s JOIN sys_user u ON u.user_id = s.user_id "
                        + "WHERE s.token_hash = ? AND s.revoked_at IS NULL AND u.enabled = TRUE "
                        + "AND (s.expires_at IS NULL OR s.expires_at > ?)",
                (result, rowNum) -> new SessionPrincipal(
                        result.getString("session_id"),
                        result.getString("user_id"),
                        result.getString("username"),
                        result.getString("purpose")),
                digest(token),
                Timestamp.from(clock.instant()));
        return matches.stream().findFirst();
    }

    public SessionPrincipal requireAdminSession(String token) {
        return requirePurpose(token, "ADMIN");
    }

    public SessionPrincipal requirePurpose(String token, String purpose) {
        SessionPrincipal principal = findActiveSession(token)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, 401, "Authentication required"));
        if (!purpose.equals(principal.purpose())) {
            throw new AuthException(HttpStatus.FORBIDDEN, 403, "This authentication context cannot access this action");
        }
        return principal;
    }

    public List<String> createRecoveryCodeBatch(String userId, Instant now) {
        String batchId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO sys_recovery_code_batch (batch_id, user_id, created_at) VALUES (?, ?, ?)",
                batchId,
                userId,
                Timestamp.from(now));
        List<RecoveryCodeService.IssuedCode> issued = recoveryCodes.generateBatch();
        List<String> clearCodes = new ArrayList<>(issued.size());
        for (RecoveryCodeService.IssuedCode code : issued) {
            jdbc.update(
                    "INSERT INTO sys_recovery_code (recovery_code_id, batch_id, code_hash, created_at) "
                            + "VALUES (?, ?, ?, ?)",
                    UUID.randomUUID().toString(),
                    batchId,
                    code.codeHash(),
                    Timestamp.from(now));
            clearCodes.add(code.clearCode());
            java.util.Arrays.fill(code.codeHash(), (byte) 0);
        }
        return List.copyOf(clearCodes);
    }

    public AdminSession createSession(
            String userId,
            String username,
            String purpose,
            List<String> recoveryCodes,
            Instant now) {
        String id = UUID.randomUUID().toString();
        String token = randomToken();
        Instant expiresAt = "RECOVERY".equals(purpose) ? now.plus(RECOVERY_CONTEXT_LIFETIME) : null;
        jdbc.update(
                "INSERT INTO sys_user_session "
                        + "(session_id, user_id, token_hash, purpose, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id,
                userId,
                digest(token),
                purpose,
                Timestamp.from(now),
                expiresAt == null ? null : Timestamp.from(expiresAt));
        writeAudit(
                userId,
                "RECOVERY".equals(purpose) ? "AUTH_RECOVERY_LOGIN_SUCCEEDED" : "AUTH_LOGIN_SUCCEEDED",
                now);
        return new AdminSession(id, username, token, purpose, List.copyOf(recoveryCodes));
    }

    private AdminRow lockUser(String username) {
        return queryUser(
                "SELECT user_id, username, password_hash, totp_secret_encrypted, totp_last_step, enabled "
                        + "FROM sys_user WHERE username = ? FOR UPDATE",
                username);
    }

    private AdminRow lockUserById(String userId) {
        return queryUser(
                "SELECT user_id, username, password_hash, totp_secret_encrypted, totp_last_step, enabled "
                        + "FROM sys_user WHERE user_id = ? FOR UPDATE",
                userId);
    }

    private AdminRow queryUser(String sql, String key) {
        List<AdminRow> rows = jdbc.query(
                sql,
                (result, rowNum) -> new AdminRow(
                        result.getString("user_id"),
                        result.getString("username"),
                        result.getString("password_hash"),
                        result.getBytes("totp_secret_encrypted"),
                        result.getObject("totp_last_step", Long.class),
                        result.getBoolean("enabled")),
                key);
        return rows.stream().findFirst().orElse(null);
    }

    private boolean isLimited(String userId, Instant now) {
        Timestamp cutoff = Timestamp.from(now.minus(LOGIN_WINDOW));
        jdbc.update("DELETE FROM sys_login_failure WHERE user_id = ? AND failed_at <= ?", userId, cutoff);
        Long failures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_login_failure WHERE user_id = ? AND failed_at > ?",
                Long.class,
                userId,
                cutoff);
        return failures != null && failures >= MAX_FAILURES;
    }

    private void recordFailure(String userId, Instant now) {
        jdbc.update(
                "INSERT INTO sys_login_failure (failure_id, user_id, failed_at) VALUES (?, ?, ?)",
                UUID.randomUUID().toString(),
                userId,
                Timestamp.from(now));
        writeAudit(userId, "AUTHENTICATION_FAILED", now);
    }

    private boolean consumeTotpStep(String userId, long step, Instant now) {
        return jdbc.update(
                "UPDATE sys_user SET totp_last_step = ?, updated_at = ? "
                        + "WHERE user_id = ? AND (totp_last_step IS NULL OR totp_last_step < ?)",
                step,
                Timestamp.from(now),
                userId,
                step) == 1;
    }

    private void revokeRecoveryCodes(String userId, Instant now) {
        jdbc.update(
                "UPDATE sys_recovery_code_batch SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                Timestamp.from(now),
                userId);
        jdbc.update(
                "UPDATE sys_recovery_code SET revoked_at = ? WHERE used_at IS NULL AND revoked_at IS NULL "
                        + "AND batch_id IN (SELECT batch_id FROM sys_recovery_code_batch WHERE user_id = ?)",
                Timestamp.from(now),
                userId);
    }

    private void requireReauth(SessionPrincipal principal, String token, boolean lock) {
        if (blank(token)) {
            throw new AuthException(HttpStatus.FORBIDDEN, 403, "Recent reauthentication is required");
        }
        String sql = "SELECT context_id FROM sys_reauth_context "
                + "WHERE token_hash = ? AND user_id = ? AND session_id = ? "
                + "AND expires_at > ? AND consumed_at IS NULL" + (lock ? " FOR UPDATE" : "");
        List<String> contexts = jdbc.query(
                sql,
                (result, rowNum) -> result.getString("context_id"),
                digest(token),
                principal.userId(),
                principal.sessionId(),
                Timestamp.from(clock.instant()));
        if (contexts.isEmpty()) {
            throw new AuthException(HttpStatus.FORBIDDEN, 403, "Recent reauthentication is required");
        }
    }

    private ReauthRow lockReauth(SessionPrincipal principal, String token) {
        if (blank(token)) {
            throw new AuthException(HttpStatus.FORBIDDEN, 403, "Recent reauthentication is required");
        }
        List<ReauthRow> rows = jdbc.query(
                "SELECT context_id, expires_at, consumed_at FROM sys_reauth_context "
                        + "WHERE token_hash = ? AND user_id = ? AND session_id = ? FOR UPDATE",
                (result, rowNum) -> new ReauthRow(
                        result.getString("context_id"),
                        result.getTimestamp("expires_at").toInstant(),
                        result.getTimestamp("consumed_at")),
                digest(token),
                principal.userId(),
                principal.sessionId());
        ReauthRow row = rows.stream().findFirst()
                .orElseThrow(() -> new AuthException(HttpStatus.FORBIDDEN, 403, "Recent reauthentication is required"));
        if (row.consumedAt() != null || !row.expiresAt().isAfter(clock.instant())) {
            throw new AuthException(HttpStatus.FORBIDDEN, 403, "Recent reauthentication is required");
        }
        return row;
    }

    private UserPendingSecret loadPendingSecret(String userId, Instant now) {
        List<UserPendingSecret> rows = jdbc.query(
                "SELECT totp_pending_secret_encrypted, totp_pending_expires_at "
                        + "FROM sys_user WHERE user_id = ? FOR UPDATE",
                (result, rowNum) -> new UserPendingSecret(
                        result.getBytes("totp_pending_secret_encrypted"),
                        result.getTimestamp("totp_pending_expires_at") == null
                                ? null : result.getTimestamp("totp_pending_expires_at").toInstant()),
                userId);
        UserPendingSecret pending = rows.stream().findFirst()
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, 401, "Authentication required"));
        if (pending.encryptedSecret() == null || pending.expiresAt() == null || !pending.expiresAt().isAfter(now)) {
            throw new AuthException(HttpStatus.CONFLICT, 409, "TOTP setup is missing or expired");
        }
        return pending;
    }

    private void consumeReauth(ReauthRow context, Instant now) {
        int changed = jdbc.update(
                "UPDATE sys_reauth_context SET consumed_at = ? WHERE context_id = ? AND consumed_at IS NULL "
                        + "AND expires_at > ?",
                Timestamp.from(now),
                context.contextId(),
                Timestamp.from(now));
        if (changed != 1) {
            throw new AuthException(HttpStatus.FORBIDDEN, 403, "Recent reauthentication is required");
        }
    }

    private void writeAudit(String userId, String action, Instant now) {
        jdbc.update(
                "INSERT INTO sys_audit_log (log_id, actor_user_id, action, metadata_json, created_at) "
                        + "VALUES (?, ?, ?, JSON_OBJECT(), ?)",
                UUID.randomUUID().toString(),
                userId,
                action,
                Timestamp.from(now));
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

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static AuthException invalidCredentials() {
        return new AuthException(HttpStatus.UNAUTHORIZED, 401, "Invalid username, password, or authentication code");
    }

    private boolean passwordMatches(String password, String encoded) {
        char[] chars = password.toCharArray();
        try {
            return passwords.matches(chars, encoded);
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }

    private long lastUsedStep(Long value) {
        return value == null ? Long.MIN_VALUE : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record LoginRequest(String username, String password, String totpCode) {
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", credentials=REDACTED]";
        }
    }

    public record RecoveryLoginRequest(String username, String password, String recoveryCode) {
        @Override
        public String toString() {
            return "RecoveryLoginRequest[username=" + username + ", credentials=REDACTED]";
        }
    }

    public record ReauthRequest(String password, String totpCode) {
        @Override
        public String toString() {
            return "ReauthRequest[credentials=REDACTED]";
        }
    }

    public record AdminSession(
            String id,
            String username,
            @JsonIgnore String token,
            String purpose,
            List<String> recoveryCodes) {
        @Override
        public String toString() {
            return "AdminSession[id=" + id + ", username=" + username + ", purpose=" + purpose
                    + ", credentials=REDACTED]";
        }
    }

    public record SessionPrincipal(String sessionId, String userId, String username, String purpose) {}

    public record SessionSummary(
            String sessionId, String purpose, boolean current, boolean revoked) {}

    public record ReauthGrant(@JsonIgnore String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "ReauthGrant[expiresAt=" + expiresAt + ", token=REDACTED]";
        }
    }

    public record TotpEnrollment(String secret, String otpauthUri) {
        @Override
        public String toString() {
            return "TotpEnrollment[credentials=REDACTED]";
        }
    }

    private record AdminRow(
            String userId,
            String username,
            String passwordHash,
            byte[] encryptedTotpSecret,
            Long lastUsedStep,
            boolean enabled) {}

    private record ReauthRow(String contextId, Instant expiresAt, Timestamp consumedAt) {}

    private record UserPendingSecret(byte[] encryptedSecret, Instant expiresAt) {}

    private record ReauthSession(String token, Instant expiresAt) {}

    private record AuthOutcome(Object value, AuthException error) {
        static AuthOutcome failed(AuthException error) {
            return new AuthOutcome(null, error);
        }

        static AuthOutcome success(Object value) {
            return new AuthOutcome(value, null);
        }

        AdminSession unwrap() {
            Object value = unwrapValue();
            if (value instanceof AdminSession session) {
                return session;
            }
            throw new IllegalStateException("Authentication result has an unexpected type");
        }

        Object unwrapValue() {
            if (error != null) {
                throw error;
            }
            return value;
        }
    }
}
