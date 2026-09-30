package com.aisys.evaluation.mq;

import com.aisys.common.mq.message.BaseMessage;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.Map;

/**
 * 任务状态回写消息（消费 ← task.status exchange）。
 * resource 执行完成后发布；本服务仅处理 taskType=EVALUATION 的消息。
 */
public class TaskStatusMessage extends BaseMessage {

    public static final String TYPE = "TASK_STATUS";

    private String taskType;          // EVALUATION / TRAINING / ...
    private Long taskId;              // 父任务 id（evaluation_task.id）
    private Long subtaskId;           // 子任务 id
    private Long modelVersionId;
    private String status;            // running / completed / failed
    // 生产者(resource)字段名为 nodeId / message，消费侧为 assignedNodeId / errorMessage；@JsonAlias 兼容。
    @JsonAlias("nodeId")
    private Long assignedNodeId;
    @JsonAlias("message")
    private String errorMessage;
    /** overall_scores（resource 计算后回写） */
    private Map<String, Object> overallScores;
    /** category_scores（分维度） */
    private Map<String, Object> categoryScores;
    private Integer sampleCount;
    private String detailPath;
    private Integer progress;

    @Override
    public String messageType() {
        return TYPE;
    }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getSubtaskId() { return subtaskId; }
    public void setSubtaskId(Long subtaskId) { this.subtaskId = subtaskId; }
    public Long getModelVersionId() { return modelVersionId; }
    public void setModelVersionId(Long modelVersionId) { this.modelVersionId = modelVersionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAssignedNodeId() { return assignedNodeId; }
    public void setAssignedNodeId(Long assignedNodeId) { this.assignedNodeId = assignedNodeId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Map<String, Object> getOverallScores() { return overallScores; }
    public void setOverallScores(Map<String, Object> overallScores) { this.overallScores = overallScores; }
    public Map<String, Object> getCategoryScores() { return categoryScores; }
    public void setCategoryScores(Map<String, Object> categoryScores) { this.categoryScores = categoryScores; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public String getDetailPath() { return detailPath; }
    public void setDetailPath(String detailPath) { this.detailPath = detailPath; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
}
