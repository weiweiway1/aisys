package com.aisys.storage.constant;

import com.aisys.common.core.response.ErrorCode;

/** Storage Service 错误码（服务号 700，DDD 3.4）。 */
public enum StorageErrorCode implements ErrorCode {
    BAD_REQUEST(700001, "请求参数错误"),
    POOL_NOT_FOUND(700002, "存储池不存在"),
    POOL_NAME_EXISTS(700003, "存储池名称已存在"),
    QUOTA_EXCEEDED(700004, "存储配额已超额"),
    INVALID_PATH(700005, "非法的文件路径（路径穿越或格式错误）"),
    FILE_NOT_FOUND(700006, "文件对象不存在"),
    UPLOAD_INITIATE_FAILED(700007, "秒传/上传初始化失败"),
    UPLOAD_COMPLETE_FAILED(700008, "分片上传完成校验失败"),
    POOL_INACTIVE(700009, "存储池不可用"),
    TENANT_REQUIRED(700010, "缺少租户上下文"),
    INTERNAL_ERROR(700999, "存储服务内部错误");

    private final int code;
    private final String message;

    StorageErrorCode(int code, String message) { this.code = code; this.message = message; }
    @Override public int code() { return code; }
    @Override public String message() { return message; }
}
