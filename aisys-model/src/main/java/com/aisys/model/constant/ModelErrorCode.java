package com.aisys.model.constant;

import com.aisys.common.core.response.ErrorCode;

/**
 * 模型服务错误码（服务号 200，格式 SSSEEE）。实现 {@link ErrorCode} 供 {@code ApiResponse.error} 使用。
 */
public enum ModelErrorCode implements ErrorCode {

    // 通用 200000
    MODEL_NOT_FOUND      (200404, "模型不存在"),
    MODEL_VERSION_NOT_FOUND(200405, "模型版本不存在"),
    MODEL_TAG_NOT_FOUND  (200406, "模型标签不存在"),

    // 业务约束 2004xx
    MODEL_NAME_DUPLICATE (200409, "模型名称在该租户内已存在"),
    VERSION_DUPLICATE    (200410, "该模型的版本号已存在"),
    INVALID_STATE_TRANSITION(200411, "非法的模型状态转换"),
    VERSION_NOT_READY    (200412, "模型版本尚未就绪（未完成上传）"),
    INVALID_CHECKSUM     (200413, "校验和不匹配"),
    UPLOAD_INIT_FAILED   (200414, "分片上传初始化失败"),
    UPLOAD_COMPLETE_FAILED(200415, "分片上传完成失败"),
    INVALID_PARTS        (200416, "分片信息不合法"),
    TENANT_REQUIRED      (200417, "缺少租户上下文"),
    BAD_REQUEST          (200400, "请求参数错误");

    private final int code;
    private final String message;

    ModelErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override public int code() { return code; }
    @Override public String message() { return message; }
}
