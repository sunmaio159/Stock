package com.aquila.common;

/**
 * 统一错误码总表。
 * 唯一事实源：落地文档设计/03_调用链与调用方式.md §三「错误码总表」。
 * 约定：HTTP 恒 200（唯一例外：限流 1004 返回 HTTP 429）；业务失败一律走 Result.code。
 */
public enum ErrorCode {

    SUCCESS(0, "success"),

    // 通用
    PARAM_MISSING(1001, "参数缺失"),
    PARAM_FORMAT_ERROR(1002, "格式错误"),
    RESOURCE_NOT_FOUND(1003, "资源不存在"),
    RATE_LIMITED(1004, "请求过于频繁"),

    // 认证与授权
    UNAUTHORIZED(2001, "未登录"),
    TOKEN_EXPIRED(2002, "Token 过期"),
    FORBIDDEN(2003, "无权限"),

    // 业务
    GROUP_LIMIT(3001, "分组超限(≤20)"),
    DSL_INVALID(3002, "告警 DSL 非法"),
    CODE_INVALID(3003, "验证码错误或过期"),
    CODE_TOO_FREQUENT(3004, "验证码过频(1条/min,10条/天)"),
    GROUP_ITEM_LIMIT(3005, "组内标的超限(≤100)"),
    SECURITY_EXISTS(3006, "标的已存在"),
    DIMENSION_NOT_OPEN(3007, "维度未开放(V2)"),
    SCORE_NOT_OPEN(3008, "评分卡未开放(V3)"),
    RULE_LIMIT(3009, "规则超限(≤50)"),
    SCOPE_INVALID(3010, "scope 非法"),

    // 采集与数据源
    SOURCE_CIRCUIT_OPEN(4001, "数据源熔断中"),
    FIELD_DRIFT(4002, "字段漂移"),
    REPLAY_WINDOW_EXCEEDED(4004, "重放窗口>7天"),
    REPLAY_CONFLICT(4005, "重放冲突"),

    // 系统
    DB_ERROR(5001, "数据库异常"),
    CACHE_ERROR(5002, "缓存异常"),
    SCHEDULE_ERROR(5003, "调度异常"),
    INTERNAL_ERROR(5000, "服务内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
