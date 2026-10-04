package com.aquila.security;

/**
 * 当前登录用户上下文（W0.3 骨架）。由 JwtAuthFilter 解析 AT 后写入。
 */
public record CurrentUser(Long uid, String role) {

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
