package com.aisys.training.constant;

import java.util.EnumSet;
import java.util.Set;

/**
 * 训练任务状态机（DDD 5.5.2）。
 * <pre>
 * pending → queued → running → (completed | failed | cancelled)
 *                 running ⇄ paused（pause / resume）
 * </pre>
 * 所有流转校验在 {@link #canTransitionTo(TaskStatus)} 中集中维护。
 */
public enum TaskStatus {

    pending,
    queued,
    running,
    paused,
    completed,
    failed,
    cancelled;

    /**
     * 校验是否允许从当前状态流转到目标状态。
     */
    public boolean canTransitionTo(TaskStatus target) {
        Set<TaskStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(this, EnumSet.noneOf(TaskStatus.class));
        return allowed.contains(target);
    }

    private static final java.util.Map<TaskStatus, Set<TaskStatus>> ALLOWED_TRANSITIONS;

    static {
        ALLOWED_TRANSITIONS = new java.util.EnumMap<>(TaskStatus.class);

        ALLOWED_TRANSITIONS.put(pending, EnumSet.of(queued, cancelled));
        ALLOWED_TRANSITIONS.put(queued, EnumSet.of(running, cancelled, failed));
        ALLOWED_TRANSITIONS.put(running, EnumSet.of(paused, completed, failed, cancelled));
        ALLOWED_TRANSITIONS.put(paused, EnumSet.of(running, queued, cancelled));
        // 终态不允许再流转
        ALLOWED_TRANSITIONS.put(completed, EnumSet.noneOf(TaskStatus.class));
        ALLOWED_TRANSITIONS.put(failed, EnumSet.noneOf(TaskStatus.class));
        ALLOWED_TRANSITIONS.put(cancelled, EnumSet.noneOf(TaskStatus.class));
    }

    public boolean isTerminal() {
        return this == completed || this == failed || this == cancelled;
    }
}
