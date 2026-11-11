package com.aquila.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aquila.auth.SessionService.SessionPrincipal;
import com.aquila.auth.SessionService.SessionSummary;
import com.aquila.common.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SessionEndpointsTest {
    private final BootstrapApplicationService bootstrap = mock(BootstrapApplicationService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final TotpService totp = mock(TotpService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(
                    new AuthController(bootstrap, sessions, totp, jdbc, clock, random),
                    new MeController(sessions))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private static Cookie sessionCookie() {
        return new Cookie(CsrfFilter.SESSION_COOKIE, "session-token");
    }

    @Test
    void meReturnsCurrentAdministratorFromSession() throws Exception {
        when(sessions.requireAdminSession(any()))
                .thenReturn(new SessionPrincipal("session-1", "user-1", "admin", "ADMIN"));

        mvc.perform(get("/api/me").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.userId").value("user-1"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void unauthenticatedMeReturns401Envelope() throws Exception {
        when(sessions.requireAdminSession(any()))
                .thenThrow(new AuthException(HttpStatus.UNAUTHORIZED, 401, "Authentication required"));

        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void sessionListMarksTheCurrentSession() throws Exception {
        when(sessions.listSessions(any())).thenReturn(List.of(
                new SessionSummary("session-1", "ADMIN", true, false),
                new SessionSummary("session-2", "ADMIN", false, true)));

        mvc.perform(get("/api/auth/sessions").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sessionId").value("session-1"))
                .andExpect(jsonPath("$.data[0].current").value(true))
                .andExpect(jsonPath("$.data[1].revoked").value(true))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void revokingAnOwnedSessionSucceeds() throws Exception {
        when(sessions.revokeOwned(any(), eq("session-2"))).thenReturn(true);

        mvc.perform(delete("/api/auth/sessions/session-2").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked").value(true));
    }

    @Test
    void revokingAnUnknownSessionReturns404Envelope() throws Exception {
        when(sessions.revokeOwned(any(), eq("missing"))).thenReturn(false);

        mvc.perform(delete("/api/auth/sessions/missing").cookie(sessionCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
