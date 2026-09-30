package com.aisys.resource.constant;

/**
 * Resource 服务常量（DDD 5.7）。
 */
public final class ResourceConstants {

    private ResourceConstants() {}

    /** 节点状态 */
    public static final String NODE_STATUS_ONLINE     = "online";
    public static final String NODE_STATUS_OFFLINE    = "offline";
    public static final String NODE_STATUS_MAINTENANCE = "maintenance";

    /** 租约状态 */
    public static final String ALLOCATION_HELD     = "held";
    public static final String ALLOCATION_RELEASED = "released";

    /** 任务类型（与 training/evaluation 一致） */
    public static final String TASK_TYPE_TRAINING   = "TRAINING";
    public static final String TASK_TYPE_EVALUATION = "EVALUATION";

    /** WebSocket 指令类型（平台 → Agent） */
    public static final String CMD_RUN_TASK    = "run_task";
    public static final String CMD_STOP_TASK   = "stop_task";
    public static final String CMD_PING        = "ping";
    public static final String CMD_SHELL       = "shell_command";

    /** WebSocket 上报类型（Agent → 平台） */
    public static final String REPORT_STATUS  = "status";
    public static final String REPORT_LOG     = "log";
    public static final String REPORT_METRICS = "metrics";
    public static final String REPORT_RESULT  = "result";
    public static final String REPORT_SHELL_RESULT = "shell_result";

    /** 消息类型 routingKey 前缀 */
    public static final String ROUTING_KEY_TASK_STATUS_PREFIX  = "task.status.";
    public static final String ROUTING_KEY_TASK_LOG_PREFIX     = "task.log.";
    public static final String ROUTING_KEY_TASK_METRICS_PREFIX = "task.metrics.";
    public static final String ROUTING_KEY_NODE_OFFLINE        = "node.offline";

    /** Redis key */
    public static final String REDIS_KEY_AGENT_TOKEN_PREFIX = "agent:token:";   // agent:token:{agentId} = agentToken
    public static final String REDIS_KEY_NODE_STATUS_PREFIX = "node:status:";   // node:status:{nodeId} = online
    public static final String REDIS_KEY_NODE_LOCK_PREFIX   = "lock:node:";     // lock:node:{nodeId} 节点调度锁
}
