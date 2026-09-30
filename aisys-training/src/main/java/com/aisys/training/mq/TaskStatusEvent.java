package com.aisys.training.mq;

import com.aisys.common.mq.message.BaseMessage;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.Instant;
import java.util.Map;

/**
 * 任务状态事件（消费）。由 Agent/Resource 发到 task.status 交换机，training 消费更新任务状态。
 * <p>路由键匹配 task.# （消费方绑定 task.status 交换机）。
 */
public class TaskStatusEvent extends BaseMessage {

    private Long taskId;
    private String status;           // running | completed | failed | cancelled | paused
    private Integer progress;
    // 生产者(resource TaskStatusMessage)字段名为 message / nodeId，消费侧为 errorMessage / assignedNodeId；
    // 用 @JsonAlias 兼容两种键，使失败原因与节点 id 正确落入。
    @JsonAlias("message")
    private String errorMessage;
    @JsonAlias("nodeId")
    private Long assignedNodeId;
    private Instant occurredAt;
    private Map<String, Object> extra;

    @Override
    public String messageType() {
        return "TASK_STATUS";
    }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getAssignedNodeId() { return assignedNodeId; }
    public void setAssignedNodeId(Long assignedNodeId) { this.assignedNodeId = assignedNodeId; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }
}
