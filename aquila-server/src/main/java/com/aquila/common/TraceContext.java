package com.aquila.common;

/**
 * traceId 上下文常量与工具。traceId 贯穿「请求→日志→审计(biz_id)」链路。
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    /** 响应/日志透出的 header 名。 */
    public static final String HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    public static String current() {
        return org.slf4j.MDC.get(MDC_KEY);
    }
}
