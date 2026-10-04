package com.aquila.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一异常处理（W0.3）。业务失败一律 HTTP 200 + Result.code（F-APP-06）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 参数缺失 → 1001。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(ErrorCode.PARAM_MISSING, "缺少参数: " + e.getParameterName());
    }

    /** 校验失败/格式错误 → 1002。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValidation(BindException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe == null ? ErrorCode.PARAM_FORMAT_ERROR.getMessage()
                : fe.getField() + " " + fe.getDefaultMessage();
        return Result.fail(ErrorCode.PARAM_FORMAT_ERROR, msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return Result.fail(ErrorCode.PARAM_FORMAT_ERROR);
    }

    /** 未匹配路径 404 → 1003（仍 HTTP 200，F-APP-06：业务接口不得泄露原生 404）。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNotFound(NoResourceFoundException e) {
        return Result.fail(ErrorCode.RESOURCE_NOT_FOUND, "接口不存在: " + e.getResourcePath());
    }

    /** 请求方法不匹配 405 → 1002（契约无专码，复用格式错误，仍 HTTP 200）。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail(ErrorCode.PARAM_FORMAT_ERROR, "请求方法不支持: " + e.getMethod());
    }

    /** 兜底 → 5000，绝不泄露堆栈到响应体。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未预期异常 traceId={}", TraceContext.current(), e);
        return Result.fail(ErrorCode.INTERNAL_ERROR);
    }
}
