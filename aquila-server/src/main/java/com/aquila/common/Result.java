package com.aquila.common;

public record Result<T>(int code, String message, T data, String traceId) {
    public static <T> Result<T> success(T data, String traceId) {
        return new Result<>(0, "OK", data, traceId);
    }

    public static <T> Result<T> error(int code, String message, String traceId) {
        return new Result<>(code, message, null, traceId);
    }
}
