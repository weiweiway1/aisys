package com.aisys.common.core.exception;

import com.aisys.common.core.response.ErrorCode;

/**
 * 通用错误码（服务号 000）：跨服务复用的通用错误。
 * 各服务自有错误码实现 ErrorCode 接口（如 ModelErrorCode）。
 */
public enum CommonErrorCode implements ErrorCode {
    SUCCESS(0, "success"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证"),
    ACCESS_DENIED(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    INTERNAL_ERROR(500, "系统内部错误"),
    SERVICE_UNAVAILABLE(503, "下游服务不可用"),
    RATE_LIMITED(429, "请求过于频繁，请稍后再试");

    private final int code;
    private final String message;

    CommonErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override public int code() { return code; }
    @Override public String message() { return message; }
}
