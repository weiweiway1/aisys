package com.aisys.dataset.constant;

import com.aisys.common.core.response.ErrorCode;

/**
 * 数据集服务错误码（服务号 300，SSSEEE = 300000 ~ 300999，DDD 3.4）。
 */
public enum DatasetErrorCode implements ErrorCode {
    DATASET_NOT_FOUND(300404, "数据集不存在"),
    DATASET_EXISTS(300409, "同名数据集已存在"),
    VERSION_NOT_FOUND(3004041, "数据集版本不存在"),
    VERSION_EXISTS(3004091, "同版本号已存在"),
    VERSION_NOT_READY(3004092, "数据集版本尚未就绪"),
    NO_PERMISSION(300403, "无权操作该数据集"),
    INVALID_FORMAT(3004001, "不支持的数据集格式"),
    PREVIEW_FAILED(3005001, "数据集预览失败"),
    STORAGE_UNAVAILABLE(3005031, "存储服务不可用");

    private final int code;
    private final String message;

    DatasetErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override public int code() { return code; }
    @Override public String message() { return message; }
}
