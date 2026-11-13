package com.aquila.auth;

import com.aquila.common.Result;
import com.aquila.common.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

public final class CsrfFilter extends OncePerRequestFilter {
    public static final String CSRF_COOKIE = "AQUILA_CSRF";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final String SESSION_COOKIE = "AQUILA_SESSION";
    public static final String RECOVERY_COOKIE = "AQUILA_RECOVERY";
    public static final String BOOTSTRAP_COOKIE = "AQUILA_BOOTSTRAP";
    public static final String REAUTH_COOKIE = "AQUILA_REAUTH";
    public static final Duration CSRF_LIFETIME = Duration.ofMinutes(30);

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String allowedOrigin;

    public CsrfFilter(
            JdbcTemplate jdbc,
            SessionService sessions,
            ObjectMapper objectMapper,
            Clock clock,
            String allowedOrigin) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.allowedOrigin = canonicalOrigin(allowedOrigin);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!isApiWrite(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || !allowedOrigin.equals(canonicalOriginOrNull(origin))) {
            reject(response, request, 403, "Request origin is not allowed");
            return;
        }

        String contextToken = cookieValue(request, CSRF_COOKIE);
        String csrfToken = request.getHeader(CSRF_HEADER);
        if (blank(contextToken) || blank(csrfToken)) {
            reject(response, request, 403, "A valid CSRF context is required");
            return;
        }

        try {
            String sessionId = currentSessionId(request);
            Long matches = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_csrf_context "
                            + "WHERE context_hash = ? AND csrf_hash = ? AND expires_at > ? "
                            + "AND (session_id <=> ?)",
                    Long.class,
                    BootstrapApplicationService.digest(contextToken),
                    BootstrapApplicationService.digest(csrfToken),
                    Timestamp.from(clock.instant()),
                    sessionId);
            if (matches == null || matches != 1L) {
                reject(response, request, 403, "A valid CSRF context is required");
                return;
            }
        } catch (RuntimeException exception) {
            reject(response, request, 503, "Security validation is temporarily unavailable");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String currentSessionId(HttpServletRequest request) {
        String token = cookieValue(request, SESSION_COOKIE);
        if (blank(token)) {
            token = cookieValue(request, RECOVERY_COOKIE);
        }
        if (blank(token)) {
            return null;
        }
        return sessions.findActiveSession(token).map(SessionService.SessionPrincipal::sessionId).orElse(null);
    }

    private boolean isApiWrite(HttpServletRequest request) {
        String method = request.getMethod();
        return request.getRequestURI().startsWith("/api/")
                && (HttpMethod.POST.matches(method)
                        || HttpMethod.PUT.matches(method)
                        || HttpMethod.PATCH.matches(method)
                        || HttpMethod.DELETE.matches(method));
    }

    public static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void reject(HttpServletResponse response, HttpServletRequest request, int status, String message)
            throws IOException {
        Object traceIdValue = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        String traceId = traceIdValue == null ? UUID.randomUUID().toString() : traceIdValue.toString();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader(TraceIdFilter.TRACE_ID_HEADER, traceId);
        objectMapper.writeValue(response.getOutputStream(), Result.error(status, message, traceId));
    }

    private static String canonicalOrigin(String value) {
        String normalized = canonicalOriginOrNull(value);
        if (normalized == null) {
            throw new IllegalStateException("STOCK_ALLOWED_ORIGIN must be an explicit HTTP(S) origin");
        }
        return normalized;
    }

    private static String canonicalOriginOrNull(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            URI origin = new URI(value);
            String scheme = origin.getScheme() == null ? "" : origin.getScheme().toLowerCase(java.util.Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https"))
                    || origin.getHost() == null
                    || origin.getRawUserInfo() != null
                    || (origin.getRawPath() != null && !origin.getRawPath().isEmpty()
                            && !origin.getRawPath().equals("/"))
                    || origin.getRawQuery() != null
                    || origin.getRawFragment() != null) {
                return null;
            }
            String host = origin.getHost().toLowerCase(java.util.Locale.ROOT);
            int port = origin.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                port = -1;
            }
            return scheme + "://" + host + (port < 0 ? "" : ":" + port);
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
