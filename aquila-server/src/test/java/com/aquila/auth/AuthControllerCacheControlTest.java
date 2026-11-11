package com.aquila.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aquila.auth.SessionService.TotpEnrollment;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerCacheControlTest {
    private final BootstrapApplicationService bootstrap = mock(BootstrapApplicationService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final TotpService totp = mock(TotpService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AuthController(bootstrap, sessions, totp, jdbc, clock, random))
            .build();

    @Test
    void totpSetupSecretResponseIsNotCacheable() throws Exception {
        when(sessions.beginTotpSetup(any(), any()))
                .thenReturn(new TotpEnrollment("TESTSECRET", "otpauth://totp/Aquila:admin?secret=TESTSECRET"));

        mvc.perform(post("/api/auth/totp/setup")
                        .cookie(new Cookie(CsrfFilter.SESSION_COOKIE, "session-token"))
                        .cookie(new Cookie(CsrfFilter.REAUTH_COOKIE, "reauth-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").value("TESTSECRET"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void recoveryRebindSecretResponseIsNotCacheable() throws Exception {
        when(sessions.beginRecoveryRebind(any()))
                .thenReturn(new TotpEnrollment("TESTSECRET", "otpauth://totp/Aquila:admin?secret=TESTSECRET"));

        mvc.perform(post("/api/auth/recovery/rebind/start")
                        .cookie(new Cookie(CsrfFilter.RECOVERY_COOKIE, "recovery-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").value("TESTSECRET"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void reauthenticationResponseIsNotCacheable() throws Exception {
        when(sessions.reauthenticate(any(), any()))
                .thenReturn(new SessionService.ReauthGrant("reauth-token", clock.instant().plusSeconds(300)));

        mvc.perform(post("/api/auth/reauth")
                        .cookie(new Cookie(CsrfFilter.SESSION_COOKIE, "session-token"))
                        .contentType("application/json")
                        .content("{\"password\":\"p\",\"totpCode\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
