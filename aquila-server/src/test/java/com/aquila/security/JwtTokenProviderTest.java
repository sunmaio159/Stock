package com.aquila.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W0.3：JWT 骨架签发/校验回归（AT/RT 类型区分、uid/role 回读）。
 */
class JwtTokenProviderTest {

    private JwtTokenProvider provider(String secret) {
        JwtProperties p = new JwtProperties();
        p.setJwtSecret(secret);
        p.setIssuer("aquila");
        p.setAccessTokenTtlSeconds(7200);
        p.setRefreshTokenTtlSeconds(604800);
        return new JwtTokenProvider(p);
    }

    @Test
    void signAndParse_accessToken() {
        JwtTokenProvider provider = provider("dev_only_secret_key_at_least_32_bytes_abcdefghijklmnop");
        String at = provider.newAccessToken(10001L, "USER");
        assertTrue(provider.isAccessToken(at));
        Claims c = provider.parse(at);
        assertEquals(10001L, provider.uidOf(c));
        assertEquals("USER", provider.roleOf(c));
    }

    @Test
    void refreshToken_isNotAccessToken() {
        JwtTokenProvider provider = provider("dev_only_secret_key_at_least_32_bytes_abcdefghijklmnop");
        String rt = provider.newRefreshToken(10001L, "USER");
        assertFalse(provider.isAccessToken(rt), "RT 不得被当作 AT 通过鉴权");
    }

    @Test
    void tamperedToken_failsParse() {
        JwtTokenProvider provider = provider("dev_only_secret_key_at_least_32_bytes_abcdefghijklmnop");
        String at = provider.newAccessToken(1L, "USER");
        assertThrows(RuntimeException.class, () -> provider.parse(at + "x"));
    }

    @Test
    void shortSecret_rejectedAtConstruction() {
        assertThrows(IllegalStateException.class, () -> provider("too_short"));
    }
}
