package com.aisys.common.core.web;

import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.exception.CommonErrorCode;
import com.aisys.common.core.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理（DDD 3.3）。仅 servlet（业务服务）生效，避免在 reactive 网关上误拦截路由异常。
 * 安全相关异常（AccessDenied/Authentication）由 common-security 的 SecurityExceptionHandler 处理。
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + defaultMsg(fe))
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(CommonErrorCode.BAD_REQUEST.code(), msg));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(CommonErrorCode.BAD_REQUEST.code(), "参数类型错误: " + ex.getName()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("业务异常 {}: {}", ex.getCode(), ex.getMessage());
        return ResponseEntity.ok(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(CommonErrorCode.BAD_REQUEST.code(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_ERROR.code(), "服务异常，请稍后重试"));
    }

    private static String defaultMsg(FieldError fe) {
        return fe.getDefaultMessage() == null ? "无效" : fe.getDefaultMessage();
    }
}
