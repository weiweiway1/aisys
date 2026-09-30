package com.aisys.common.core.exception;

import com.aisys.common.core.response.ErrorCode;

/**
 * 业务异常（DDD 3.3）。由 GlobalExceptionHandler 统一转成 ApiResponse.error。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ErrorCode ec) {
        super(ec.message());
        this.code = ec.code();
    }

    public BusinessException(ErrorCode ec, String message) {
        super(message);
        this.code = ec.code();
    }

    public int getCode() {
        return code;
    }
}
