package com.aquila.auth;

import com.aquila.auth.BootstrapApplicationService.BootstrapConfirmation;
import com.aquila.auth.SessionService.AdminSession;
import com.aquila.auth.SessionService.LoginRequest;
import com.aquila.auth.SessionService.RecoveryLoginRequest;
import com.aquila.auth.SessionService.ReauthGrant;
import com.aquila.auth.SessionService.ReauthRequest;
import com.aquila.auth.SessionService.SessionPrincipal;
import com.aquila.auth.SessionService.TotpEnrollment;
import com.aquila.common.Result;
import com.aquila.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class AuthController {
    private static final Duration SESSION_COOKIE_AGE = Duration.ofDays(365);

    private final BootstrapApplicationService bootstrap;
    private final SessionService sessions;
    private final TotpService totp;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final java.time.Clock clock;
    private final SecureRandom random;

    public AuthController(
            BootstrapApplicationService bootstrap,
            SessionService sessions,
            TotpService totp,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            java.time.Clock clock,
            SecureRandom random) {
        this.bootstrap = bootstrap;
        this.sessions = sessions;
        this.totp = totp;
        this.jdbc = jdbc;
        this.clock = clock;
        this.random = random;
    }

    @GetMapping("/csrf")
    public Result<CsrfResponse> csrf(HttpServletRequest request, HttpServletResponse response) {
        String contextToken = opaqueToken();
        String csrfToken = opaqueToken();
        String sessionToken = firstCookie(request, CsrfFilter.SESSION_COOKIE, CsrfFilter.RECOVERY_COOKIE);
        String sessionId = sessions.findActiveSession(sessionToken)
                .map(SessionPrincipal::sessionId)
                .orElse(null);
        java.time.Instant now = clock.instant();
        jdbc.update(
                "INSERT INTO sys_csrf_context "
                        + "(context_id, context_hash, csrf_hash, session_id, expires_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                java.util.UUID.randomUUID().toString(),
                BootstrapApplicationService.digest(contextToken),
                BootstrapApplicationService.digest(csrfToken),
                sessionId,
                java.sql.Timestamp.from(now.plus(CsrfFilter.CSRF_LIFETIME)),
                java.sql.Timestamp.from(now));
        setCookie(
                response,
                request,
                CsrfFilter.CSRF_COOKIE,
                contextToken,
                CsrfFilter.CSRF_LIFETIME,
                true);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return Result.success(new CsrfResponse(csrfToken), traceId(request));
    }

    @PostMapping("/bootstrap/context")
    public Result<Map<String, Boolean>> bootstrapContext(
            @RequestBody BootstrapTokenRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (body == null || !bootstrap.isBootstrapTokenUsable(body.bootstrapToken())) {
            throw new AuthException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, 401, "Invalid or expired bootstrap context");
        }
        setCookie(
                response,
                request,
                CsrfFilter.BOOTSTRAP_COOKIE,
                body.bootstrapToken(),
                BootstrapApplicationService.BOOTSTRAP_LIFETIME,
                true);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return Result.success(Map.of("accepted", true), traceId(request));
    }

    @PostMapping("/bootstrap/confirm")
    public Result<AuthResponse> confirmBootstrap(
            @RequestBody CodeRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        String bootstrapToken = CsrfFilter.cookieValue(request, CsrfFilter.BOOTSTRAP_COOKIE);
        AdminSession session = bootstrap.confirm(new BootstrapConfirmation(
                bootstrapToken,
                body == null ? null : body.code()));
        setSessionCookie(response, request, session);
        clearCookie(response, request, CsrfFilter.BOOTSTRAP_COOKIE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return Result.success(
                new AuthResponse(true, session.username(), session.purpose(), session.recoveryCodes()),
                traceId(request));
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(
            @RequestBody LoginPayload body,
            HttpServletRequest request,
            HttpServletResponse response) {
        AdminSession session = sessions.login(new LoginRequest(
                body == null ? null : body.username(),
                body == null ? null : body.password(),
                body == null ? null : body.totpCode()));
        setSessionCookie(response, request, session);
        clearCookie(response, request, CsrfFilter.RECOVERY_COOKIE);
        clearCookie(response, request, CsrfFilter.BOOTSTRAP_COOKIE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return Result.success(new AuthResponse(true, session.username(), session.purpose(), List.of()), traceId(request));
    }

    @PostMapping("/recovery-login")
    public Result<AuthResponse> recoveryLogin(
            @RequestBody RecoveryLoginPayload body,
            HttpServletRequest request,
            HttpServletResponse response) {
        AdminSession session = sessions.recoveryLogin(new RecoveryLoginRequest(
                body == null ? null : body.username(),
                body == null ? null : body.password(),
                body == null ? null : body.recoveryCode()));
        setCookie(
                response,
                request,
                CsrfFilter.RECOVERY_COOKIE,
                session.token(),
                SessionService.RECOVERY_CONTEXT_LIFETIME,
                true);
        clearCookie(response, request, CsrfFilter.SESSION_COOKIE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return Result.success(
                new AuthResponse(true, session.username(), session.purpose(), List.of()),
                traceId(request));
    }

    @PostMapping("/recovery/rebind/start")
    public Result<TotpSetupResponse> beginRecoveryRebind(HttpServletRequest request) {
        TotpEnrollment enrollment =
                sessions.beginRecoveryRebind(CsrfFilter.cookieValue(request, CsrfFilter.RECOVERY_COOKIE));
        return Result.success(
                new TotpSetupResponse(enrollment.secret(), enrollment.otpauthUri()),
                traceId(request));
    }

    @PostMapping("/recovery/rebind/confirm")
    public Result<RecoveryCodesResponse> confirmRecoveryRebind(
            @RequestBody CodeRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        List<String> codes = sessions.confirmRecoveryRebind(
                CsrfFilter.cookieValue(request, CsrfFilter.RECOVERY_COOKIE),
                body == null ? null : body.code());
        clearCookie(response, request, CsrfFilter.RECOVERY_COOKIE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return Result.success(new RecoveryCodesResponse(codes, true, true), traceId(request));
    }

    @PostMapping("/logout")
    public Result<Map<String, Boolean>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        String token = firstCookie(request, CsrfFilter.SESSION_COOKIE, CsrfFilter.RECOVERY_COOKIE);
        sessions.findActiveSession(token).ifPresent(principal -> sessions.revoke(principal.sessionId()));
        clearCookie(response, request, CsrfFilter.SESSION_COOKIE);
        clearCookie(response, request, CsrfFilter.RECOVERY_COOKIE);
        clearCookie(response, request, CsrfFilter.REAUTH_COOKIE);
        clearCookie(response, request, CsrfFilter.BOOTSTRAP_COOKIE);
        return Result.success(Map.of("loggedOut", true), traceId(request));
    }

    @GetMapping("/session")
    public Result<AuthResponse> session(HttpServletRequest request) {
        String token = firstCookie(request, CsrfFilter.SESSION_COOKIE, CsrfFilter.RECOVERY_COOKIE);
        SessionPrincipal principal = sessions.findActiveSession(token)
                .orElseThrow(() -> new AuthException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, 401, "Authentication required"));
        return Result.success(
                new AuthResponse(true, principal.username(), principal.purpose(), List.of()),
                traceId(request));
    }

    @PostMapping("/reauth")
    public Result<ReauthResponse> reauthenticate(
            @RequestBody ReauthPayload body,
            HttpServletRequest request,
            HttpServletResponse response) {
        ReauthGrant grant = sessions.reauthenticate(
                CsrfFilter.cookieValue(request, CsrfFilter.SESSION_COOKIE),
                new ReauthRequest(
                        body == null ? null : body.password(),
                        body == null ? null : body.totpCode()));
        Duration maxAge = Duration.between(clock.instant(), grant.expiresAt());
        setCookie(response, request, CsrfFilter.REAUTH_COOKIE, grant.token(), maxAge, true);
        return Result.success(new ReauthResponse(grant.expiresAt()), traceId(request));
    }

    @PostMapping("/totp/setup")
    public Result<TotpSetupResponse> beginTotpSetup(HttpServletRequest request) {
        TotpEnrollment enrollment = sessions.beginTotpSetup(
                CsrfFilter.cookieValue(request, CsrfFilter.SESSION_COOKIE),
                CsrfFilter.cookieValue(request, CsrfFilter.REAUTH_COOKIE));
        return Result.success(
                new TotpSetupResponse(enrollment.secret(), enrollment.otpauthUri()),
                traceId(request));
    }

    @PostMapping("/totp/confirm")
    public Result<RecoveryCodesResponse> confirmTotpSetup(
            @RequestBody CodeRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        List<String> codes = sessions.confirmTotpSetup(
                CsrfFilter.cookieValue(request, CsrfFilter.SESSION_COOKIE),
                CsrfFilter.cookieValue(request, CsrfFilter.REAUTH_COOKIE),
                body == null ? null : body.code());
        clearCookie(response, request, CsrfFilter.REAUTH_COOKIE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return Result.success(new RecoveryCodesResponse(codes, true, false), traceId(request));
    }

    @PostMapping("/recovery-codes/rotate")
    public Result<RecoveryCodesResponse> rotateRecoveryCodes(
            HttpServletRequest request,
            HttpServletResponse response) {
        List<String> codes = sessions.rotateRecoveryCodes(
                CsrfFilter.cookieValue(request, CsrfFilter.SESSION_COOKIE),
                CsrfFilter.cookieValue(request, CsrfFilter.REAUTH_COOKIE));
        clearCookie(response, request, CsrfFilter.REAUTH_COOKIE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return Result.success(new RecoveryCodesResponse(codes, true, false), traceId(request));
    }

    private void setSessionCookie(HttpServletResponse response, HttpServletRequest request, AdminSession session) {
        setCookie(response, request, CsrfFilter.SESSION_COOKIE, session.token(), SESSION_COOKIE_AGE, true);
    }

    private void setCookie(
            HttpServletResponse response,
            HttpServletRequest request,
            String name,
            String value,
            Duration maxAge,
            boolean httpOnly) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .secure(request.isSecure())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearCookie(HttpServletResponse response, HttpServletRequest request, String name) {
        setCookie(response, request, name, "", Duration.ZERO, true);
    }

    private String firstCookie(HttpServletRequest request, String first, String second) {
        String value = CsrfFilter.cookieValue(request, first);
        return value == null ? CsrfFilter.cookieValue(request, second) : value;
    }

    private String opaqueToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        try {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return traceId == null ? null : traceId.toString();
    }

    public record BootstrapTokenRequest(String bootstrapToken) {
        @Override
        public String toString() {
            return "BootstrapTokenRequest[credentials=REDACTED]";
        }
    }

    public record LoginPayload(String username, String password, String totpCode) {
        @Override
        public String toString() {
            return "LoginPayload[username=" + username + ", credentials=REDACTED]";
        }
    }

    public record RecoveryLoginPayload(String username, String password, String recoveryCode) {
        @Override
        public String toString() {
            return "RecoveryLoginPayload[username=" + username + ", credentials=REDACTED]";
        }
    }

    public record ReauthPayload(String password, String totpCode) {
        @Override
        public String toString() {
            return "ReauthPayload[credentials=REDACTED]";
        }
    }

    public record CodeRequest(String code) {
        @Override
        public String toString() {
            return "CodeRequest[credentials=REDACTED]";
        }
    }

    public record CsrfResponse(String token) {
        @Override
        public String toString() {
            return "CsrfResponse[token=REDACTED]";
        }
    }

    public record AuthResponse(
            boolean authenticated, String username, String purpose, List<String> recoveryCodes) {
        @Override
        public String toString() {
            return "AuthResponse[authenticated=" + authenticated + ", username=" + username
                    + ", purpose=" + purpose + ", credentials=REDACTED]";
        }
    }

    public record TotpSetupResponse(String secret, String otpauthUri) {
        @Override
        public String toString() {
            return "TotpSetupResponse[credentials=REDACTED]";
        }
    }

    public record RecoveryCodesResponse(List<String> codes, boolean shownOnce, boolean requiresLogin) {
        @Override
        public String toString() {
            return "RecoveryCodesResponse[credentials=REDACTED]";
        }
    }

    public record ReauthResponse(java.time.Instant expiresAt) {}
}
