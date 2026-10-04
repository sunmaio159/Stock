package com.aquila.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 骨架（W0.3）：签发/校验 AT、RT。
 * AT 载荷含 uid + role + jti；RT 含 jti（供服务端 auth:rt:{userId}:{jti} 记录与旋转校验，见 P1-01）。
 * 说明：本类只做令牌编解码，会话吊销/幂等等 Redis 落点在 W1 登录任务实现。
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_UID = "uid";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "AT";
    private static final String TYPE_REFRESH = "RT";

    private final SecretKey key;
    private final JwtProperties props;

    public JwtTokenProvider(JwtProperties props) {
        this.props = props;
        String secretStr = props.getJwtSecret();
        if (secretStr == null || secretStr.isBlank()) {
            throw new IllegalStateException(
                    "aquila.security.jwt-secret 未配置：生产必须设置环境变量 AQUILA_JWT_SECRET（本地可启用 dev profile）");
        }
        byte[] secret = secretStr.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("aquila.security.jwt-secret 长度必须 >= 32 字节（HS256）");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String newAccessToken(Long uid, String role) {
        return build(uid, role, TYPE_ACCESS, props.getAccessTokenTtlSeconds());
    }

    public String newRefreshToken(Long uid, String role) {
        return build(uid, role, TYPE_REFRESH, props.getRefreshTokenTtlSeconds());
    }

    private String build(Long uid, String role, String type, long ttlSeconds) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlSeconds * 1000L);
        return Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setIssuer(props.getIssuer())
                .setSubject(String.valueOf(uid))
                .claim(CLAIM_UID, uid)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TYPE, type)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** 解析并校验签名/过期，返回 Claims；非法令牌抛 JwtException。 */
    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .requireIssuer(props.getIssuer())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public boolean isAccessToken(String token) {
        try {
            return TYPE_ACCESS.equals(parse(token).get(CLAIM_TYPE, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long uidOf(Claims claims) {
        return claims.get(CLAIM_UID, Long.class);
    }

    public String roleOf(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }

    public long getAccessTokenTtlSeconds() {
        return props.getAccessTokenTtlSeconds();
    }

    public long getRefreshTokenTtlSeconds() {
        return props.getRefreshTokenTtlSeconds();
    }
}
