package com.aisys.common.core.constant;

/**
 * 平台通用常量：网关↔服务间传递身份/链路的请求头（DDD 4.1.2）。
 */
public final class CommonConstants {

    private CommonConstants() {}

    /** 用户 ID（Long） */
    public static final String HEADER_USER_ID = "X-User-Id";
    /** 租户 ID（Long，超管为空） */
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    /** 角色列表（逗号分隔，如 ROLE_ADMIN,ROLE_PLATFORM_ADMIN） */
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    /** 用户名 */
    public static final String HEADER_USERNAME = "X-Username";
    /** 链路追踪 ID（无则由网关生成 UUID） */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** Agent 身份头（Agent 链路，不走 JWT 用户链路） */
    public static final String HEADER_AGENT_ID = "X-Agent-Id";

    public static final String ROLE_PLATFORM_ADMIN = "ROLE_PLATFORM_ADMIN";
    public static final String ROLE_TENANT_ADMIN = "ROLE_TENANT_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";

    public static final String TRACE_ID_MDC_KEY = "traceId";

    /** RabbitMQ exchange（DDD 4.3.1） */
    public static final String EXCHANGE_TASK_COMMAND = "task.command";
    public static final String EXCHANGE_TASK_STATUS = "task.status";
    public static final String EXCHANGE_TASK_LOG = "task.log";
    public static final String EXCHANGE_TASK_METRICS = "task.metrics";
    public static final String EXCHANGE_NOTIFICATION_EVENT = "notification.event";
    public static final String EXCHANGE_STORAGE_EVENT = "storage.event";
}
