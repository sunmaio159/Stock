package com.aquila.common;

import com.aquila.auth.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Result<Void>> handleAuthentication(
            AuthException exception,
            HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(exception.status())
                .body(Result.error(
                        exception.code(),
                        exception.getMessage(),
                        traceId == null ? null : traceId.toString()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result<Void>> handleApi(
            ApiException exception,
            HttpServletRequest request) {
        return error(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(
            DuplicateKeyException exception,
            HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, 409, "Resource already exists", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(404, "Resource not found", traceId == null ? null : traceId.toString()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            BindException.class
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, 400, "Invalid request", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, 415, "Unsupported media type", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleUnsupportedMethod(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, 405, "Method not allowed", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        log.error("Unhandled request failure traceId={} type={}",
                traceId, exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, 500, "Internal server error", request);
    }

    private ResponseEntity<Result<Void>> error(
            HttpStatus status, int code, String message, HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(status)
                .body(Result.error(code, message, traceId == null ? null : traceId.toString()));
    }
}
