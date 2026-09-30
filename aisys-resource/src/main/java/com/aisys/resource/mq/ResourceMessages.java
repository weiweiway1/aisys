package com.aisys.resource.mq;

import com.aisys.common.mq.message.BaseMessage;

import java.util.Map;

/**
 * Resource 服务转发的 MQ 消息（Agent 经 WebSocket 上报 → Resource → 对应 exchange）。
 * <p>均继承 {@link BaseMessage}，由 {@link com.aisys.common.mq.outbox.EventPublisher} 经 Outbox 投递。
 */
public final class ResourceMessages {

    private ResourceMessages() {}

    /** 任务状态变更（task.status）。status: RUNNING/SUCCEEDED/FAILED */
    public static class TaskStatusMessage extends BaseMessage {
        private String taskType;     // TRAINING/EVALUATION
        private Long taskId;
        private String status;
        private String message;
        private Long nodeId;
        private Integer progress;
        // 评测子任务关联 + 结果分数（EVALUATION 用；TRAINING 留空）。由 ForwardService 从调度缓存/Agent 上报恢复。
        private Long subtaskId;
        private Long modelVersionId;
        private Map<String, Object> overallScores;
        private Map<String, Object> categoryScores;
        private Integer sampleCount;
        private String detailPath;
        private Map<String, Object> extra;

        @Override public String messageType() { return "TASK_STATUS"; }
        public String getTaskType() { return taskType; }
        public void setTaskType(String taskType) { this.taskType = taskType; }
        public Long getTaskId() { return taskId; }
        public void setTaskId(Long taskId) { this.taskId = taskId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Long getNodeId() { return nodeId; }
        public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
        public Integer getProgress() { return progress; }
        public void setProgress(Integer progress) { this.progress = progress; }
        public Long getSubtaskId() { return subtaskId; }
        public void setSubtaskId(Long subtaskId) { this.subtaskId = subtaskId; }
        public Long getModelVersionId() { return modelVersionId; }
        public void setModelVersionId(Long modelVersionId) { this.modelVersionId = modelVersionId; }
        public Map<String, Object> getOverallScores() { return overallScores; }
        public void setOverallScores(Map<String, Object> overallScores) { this.overallScores = overallScores; }
        public Map<String, Object> getCategoryScores() { return categoryScores; }
        public void setCategoryScores(Map<String, Object> categoryScores) { this.categoryScores = categoryScores; }
        public Integer getSampleCount() { return sampleCount; }
        public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
        public String getDetailPath() { return detailPath; }
        public void setDetailPath(String detailPath) { this.detailPath = detailPath; }
        public Map<String, Object> getExtra() { return extra; }
        public void setExtra(Map<String, Object> extra) { this.extra = extra; }
    }

    /** 任务日志（task.log）。level: INFO/WARN/ERROR */
    public static class TaskLogMessage extends BaseMessage {
        private String taskType;
        private Long taskId;
        private String level;
        private String message;
        private Long nodeId;

        @Override public String messageType() { return "TASK_LOG"; }
        public String getTaskType() { return taskType; }
        public void setTaskType(String taskType) { this.taskType = taskType; }
        public Long getTaskId() { return taskId; }
        public void setTaskId(Long taskId) { this.taskId = taskId; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Long getNodeId() { return nodeId; }
        public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    }

    /** 任务指标（task.metrics） */
    public static class TaskMetricsMessage extends BaseMessage {
        private String taskType;
        private Long taskId;
        private Long nodeId;
        private Map<String, Object> metrics;
        // 训练 epoch/step（Agent 从容器 metric 帧 obj 透传），前端图表 x 轴用。
        private Long step;
        // 指标产生时刻（容器 emit 时刻，Agent 透传）；为空时 training 侧用入库时刻。
        private java.time.Instant ts;

        @Override public String messageType() { return "TASK_METRICS"; }
        public String getTaskType() { return taskType; }
        public void setTaskType(String taskType) { this.taskType = taskType; }
        public Long getTaskId() { return taskId; }
        public void setTaskId(Long taskId) { this.taskId = taskId; }
        public Long getNodeId() { return nodeId; }
        public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
        public Map<String, Object> getMetrics() { return metrics; }
        public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }
        public Long getStep() { return step; }
        public void setStep(Long step) { this.step = step; }
        public java.time.Instant getTs() { return ts; }
        public void setTs(java.time.Instant ts) { this.ts = ts; }
    }

    /** 节点离线通知（notification.event） */
    public static class NodeOfflineMessage extends BaseMessage {
        private Long nodeId;
        private String nodeName;
        private String agentId;
        private String reason;

        @Override public String messageType() { return "NODE_OFFLINE"; }
        public Long getNodeId() { return nodeId; }
        public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
        public String getNodeName() { return nodeName; }
        public void setNodeName(String nodeName) { this.nodeName = nodeName; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
