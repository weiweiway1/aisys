package com.aisys.common.security.web;

import com.aisys.common.core.exception.CommonErrorCode;
import com.aisys.common.core.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 安全与路由相关异常处理（高优先级，先于 common-core 的兜底处理器）。
 * <p>{@code @PreAuthorize} 抛 {@link AccessDeniedException} → 403；未认证 → 401。
 * <p>缺失路由（{@link NoResourceFoundException}/{@link NoHandlerFoundException}）→ 404；
 * 不支持的请求方法（{@link HttpRequestMethodNotSupportedException}）→ 405。避免被兜底吞成 500。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(CommonErrorCode.ACCESS_DENIED));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(CommonErrorCode.UNAUTHORIZED));
    }

    /** 缺失路由 / 静态资源未找到 → 404（而非兜底 500）。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(CommonErrorCode.NOT_FOUND.code(), "接口不存在"));
    }

    /** 请求方法不支持 → 405（而非兜底 500）。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(HttpStatus.METHOD_NOT_ALLOWED.value(), "请求方法不支持: " + ex.getMethod()));
    }
}
