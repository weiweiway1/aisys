package com.aisys.common.core.response;

import java.time.Instant;

/**
 * 统一响应体（DDD 3.2）。
 * <p>code = 0 表示成功；非 0 为 6 位错误码 SSSEEE（DDD 3.4）。
 */
public record ApiResponse<T>(int code, String message, T data, String timestamp) {

    private static String now() {
        return Instant.now().toString();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data, now());
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, now());
    }

    public static <T> ApiResponse<T> error(ErrorCode ec) {
        return new ApiResponse<>(ec.code(), ec.message(), null, now());
    }

    public boolean isSuccess() {
        return code == 0;
    }
}
