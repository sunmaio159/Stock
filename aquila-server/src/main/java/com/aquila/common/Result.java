package com.aquila.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

/**
 * 统一响应封装 {code, message, data, traceId}。
 * 契约：落地文档设计/03 §三「通用约定」。
 * - HTTP 恒 200（限流除外）；业务失败通过 code 表达。
 * - traceId 取自 MDC（W0.3 traceId 注入）。
 * - 四字段恒定输出（含 data:null），对齐 03 §3.1 logout 示例 {data:null}，
 *   故本类显式 ALWAYS 覆盖全局 non_null。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = MDC.get(TraceContext.MDC_KEY);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static Result<Void> ok() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode ec) {
        return new Result<>(ec.getCode(), ec.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode ec, String message) {
        return new Result<>(ec.getCode(), message, null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
