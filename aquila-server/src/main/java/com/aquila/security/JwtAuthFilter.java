package com.aquila.security;

import com.aquila.common.ErrorCode;
import com.aquila.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 鉴权过滤器骨架（W0.3）。
 * 白名单外的请求需 Authorization: Bearer <AT>；缺失/非法 → 2001，过期 → 2002（HTTP 恒 200，F-APP-06）。
 * 仅登录/刷新/健康检查公开；logout 是写操作需携 Bearer（见 03 “除登录/刷新外全部需 Bearer”）。会话吊销/RT 旋转在 W1 实现。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 公开路径白名单（仅登录/刷新/验证码/健康检查；logout 需 Bearer，不入白名单）。 */
    private static final List<String> WHITELIST = List.of(
            "/actuator/**", "/api/auth/login", "/api/auth/send-code",
            "/api/auth/refresh", "/error");

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final JwtTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtTokenProvider tokenProvider, ObjectMapper objectMapper) {
        this.tokenProvider = tokenProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (isPublic(path)) {
            chain.doFilter(req, resp);
            return;
        }

        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeError(resp, ErrorCode.UNAUTHORIZED);
            return;
        }
        String token = header.substring(7).trim();
        try {
            Claims claims = tokenProvider.parse(token);
            if (!tokenProvider.isAccessToken(token)) {
                writeError(resp, ErrorCode.UNAUTHORIZED);
                return;
            }
            req.setAttribute("currentUser",
                    new CurrentUser(tokenProvider.uidOf(claims), tokenProvider.roleOf(claims)));
            chain.doFilter(req, resp);
        } catch (JwtException | IllegalArgumentException e) {
            writeError(resp, ErrorCode.TOKEN_EXPIRED);
        }
    }

    private boolean isPublic(String path) {
        return WHITELIST.stream().anyMatch(p -> MATCHER.match(p, path));
    }

    private void writeError(HttpServletResponse resp, ErrorCode ec) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(resp.getWriter(), Result.fail(ec));
    }
}
