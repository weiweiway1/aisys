package com.aisys.evaluation.constant;

/**
 * 评测服务常量。
 */
public final class EvaluationConstants {

    private EvaluationConstants() {}

    /** 任务状态 */
    public static final String STATUS_PENDING    = "pending";
    public static final String STATUS_RUNNING    = "running";
    public static final String STATUS_COMPLETED  = "completed";
    public static final String STATUS_COMPLETED_WITH_ERRORS = "completed_with_errors";
    public static final String STATUS_FAILED     = "failed";
    public static final String STATUS_STOPPED    = "stopped";
    public static final String STATUS_CANCELED   = "canceled";

    /** benchmark 状态 */
    public static final String BENCHMARK_ACTIVE   = "active";
    public static final String BENCHMARK_ARCHIVED = "archived";

    /** 任务类型（task.command 消息） */
    public static final String TASK_TYPE_EVALUATION = "EVALUATION";

    /** 路由 key：与 Resource 服务 ROUTING_KEY_TASK_STATUS_PREFIX + lower(taskType) 对齐 */
    public static final String ROUTING_KEY_EVALUATION_COMMAND = "task.command.eval";
    public static final String ROUTING_KEY_EVALUATION_STATUS  = "task.status.evaluation";

    /** 排行榜默认排序指标（目标检测平台默认 mAP50；分类场景由 benchmark metrics_config 指定 accuracy） */
    public static final String DEFAULT_SORT_METRIC = "mAP50";
}
