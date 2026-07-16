package com.aisys.model.constant;

/**
 * 模型服务常量（DDD 5.3）。
 */
public final class ModelConstants {

    private ModelConstants() {}

    /** 模型状态机：draft → published → deprecated → archived */
    public static final String STATUS_DRAFT     = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_DEPRECATED= "deprecated";
    public static final String STATUS_ARCHIVED  = "archived";

    /** 模型版本状态：creating → ready */
    public static final String VERSION_STATUS_CREATING = "creating";
    public static final String VERSION_STATUS_READY    = "ready";
    public static final String VERSION_STATUS_FAILED   = "failed";

    /**
     * 模型镜像 tar 的 S3 对象 key（relPath，不含租户段）。
     * 实际全 key = {tenantId}/models/{modelId}/{version}/model.bin（由存储模块/Resource 拼租户前缀），
     * 与数据集 storagePath 约定一致，便于 Resource 统一预签名下发 Agent。
     */
    public static final String STORAGE_KEY_FORMAT = "models/%d/%s/model.bin";

    /** 消息类型（写入 outbox 的 messageType） */
    public static final String MSG_MODEL_PUBLISHED  = "MODEL_PUBLISHED";
    public static final String MSG_MODEL_DEPRECATED = "MODEL_DEPRECATED";
    public static final String MSG_MODEL_ARCHIVED   = "MODEL_ARCHIVED";
    public static final String MSG_MODEL_DELETED    = "MODEL_DELETED";

    /** 聚合类型 */
    public static final String AGGREGATE_MODEL = "MODEL";
}
