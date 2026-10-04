package com.aquila.common;

/**
 * 业务异常：携带 ErrorCode，由 GlobalExceptionHandler 统一转 Result（HTTP 恒 200）。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode ec) {
        super(ec.getMessage());
        this.code = ec.getCode();
    }

    public BizException(ErrorCode ec, String message) {
        super(message);
        this.code = ec.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BizException of(ErrorCode ec) {
        return new BizException(ec);
    }
}
