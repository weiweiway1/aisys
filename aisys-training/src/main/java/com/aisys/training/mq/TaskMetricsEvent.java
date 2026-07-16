package com.aisys.training.mq;

import com.aisys.common.mq.message.BaseMessage;

import java.time.Instant;
import java.util.Map;

/**
 * 任务指标事件（消费）。由 Agent 高频发到 task.metrics 交换机，training 按窗口降采样后写 task_metric 表。
 */
public class TaskMetricsEvent extends BaseMessage {

    private Long taskId;
    private Long step;
    private Instant ts;
    private Map<String, Object> metrics;

    @Override
    public String messageType() {
        return "TASK_METRICS";
    }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getStep() { return step; }
    public void setStep(Long step) { this.step = step; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
    public Map<String, Object> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }
}
