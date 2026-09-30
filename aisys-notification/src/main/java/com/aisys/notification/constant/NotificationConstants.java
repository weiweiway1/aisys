package com.aisys.notification.constant;

/**
 * Notification Service 常量（DDD 5.9）。
 */
public final class NotificationConstants {

    private NotificationConstants() {}

    /** 通知类型（type） */
    public static final String TYPE_TASK_COMPLETED = "TASK_COMPLETED";
    public static final String TYPE_NODE_OFFLINE = "NODE_OFFLINE";
    public static final String TYPE_SYSTEM = "SYSTEM";

    /** 通知级别（level） */
    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_WARNING = "warning";
    public static final String LEVEL_ERROR = "error";
    public static final String LEVEL_SUCCESS = "success";

    /** 规则目标类型（target_type） */
    public static final String TARGET_USER = "USER";
    public static final String TARGET_ROLE = "ROLE";
    public static final String TARGET_TENANT = "TENANT";

    /** 分发渠道（channels） */
    public static final String CHANNEL_IN_APP = "IN_APP";
    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_WEBHOOK = "WEBHOOK";

    /** 关联资源类型（ref_type） */
    public static final String REF_TASK = "TASK";
    public static final String REF_NODE = "NODE";

    /** RabbitMQ 队列/路由键（绑定模式用 # 全量匹配：EventPublisher 的 outbox routingKey = aggregateId，不匹配前缀模式） */
    public static final String QUEUE_NOTIFICATION_EVENT = "q.notification.event";
    public static final String ROUTING_NOTIFICATION_EVENT = "#";

    public static final String QUEUE_STORAGE_EVENT = "q.notification.storage";
    public static final String ROUTING_STORAGE_EVENT = "#";

    /** WebSocket 端点与首帧鉴权字段 */
    public static final String WS_PATH = "/ws/v1/notifications/stream";
    public static final String WS_AUTH_FIELD = "accessToken";
}
