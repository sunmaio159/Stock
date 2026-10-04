package com.aquila.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全相关配置（映射 application.yml 的 aquila.security.*）。
 * AT 2h / RT 7d；secret 必须环境变量注入（禁止用默认值上生产）。
 */
@ConfigurationProperties(prefix = "aquila.security")
public class JwtProperties {

    private String jwtSecret;
    private long accessTokenTtlSeconds = 7200;
    private long refreshTokenTtlSeconds = 604800;
    private String issuer = "aquila";

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public void setAccessTokenTtlSeconds(long v) {
        this.accessTokenTtlSeconds = v;
    }

    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public void setRefreshTokenTtlSeconds(long v) {
        this.refreshTokenTtlSeconds = v;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
