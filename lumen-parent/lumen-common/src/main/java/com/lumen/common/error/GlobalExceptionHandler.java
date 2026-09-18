package com.lumen.common.error;

import com.lumen.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException ex, HttpServletRequest req) {
        ErrorCode ec = ex.getErrorCode();
        log.warn("BizException at {} {}: code={} msg={}", req.getMethod(), req.getRequestURI(), ec.getCode(), ex.getMessage());
        return ResponseEntity.status(ec.getHttpStatus()).body(R.fail(ec.getCode(), ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<R<Void>> handleValidation(Exception ex) {
        List<FieldError> fieldErrors = (ex instanceof MethodArgumentNotValidException manve)
                ? manve.getBindingResult().getFieldErrors()
                : ((BindException) ex).getFieldErrors();
        Map<String, String> errors = fieldErrors.stream()
                .collect(Collectors.toMap(FieldError::getField, e -> e.getDefaultMessage() == null ? "" : e.getDefaultMessage(), (a, b) -> a));
        R<Void> body = R.fail(CommonErrorCode.PARAM_INVALID.getCode(), "参数校验失败");
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(R.fail(CommonErrorCode.FORBIDDEN.getCode(), "无权限"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(401).body(R.fail(CommonErrorCode.UNAUTHORIZED.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleAny(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception at {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(500).body(R.fail(CommonErrorCode.INTERNAL_ERROR.getCode(), "服务器内部错误"));
    }
}