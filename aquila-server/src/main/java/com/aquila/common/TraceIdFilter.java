package com.aquila.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 注入过滤器（W0.3）。
 * 入口生成/透传 traceId → MDC → 响应头 X-Trace-Id；请求结束清理 MDC 防线程复用串号。
 * 对应调用链 3：JWT Filter → 限流 Filter → traceId 注入。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String traceId = req.getHeader(TraceContext.HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TraceContext.MDC_KEY, traceId);
        resp.setHeader(TraceContext.HEADER, traceId);
        try {
            chain.doFilter(req, resp);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }
}
