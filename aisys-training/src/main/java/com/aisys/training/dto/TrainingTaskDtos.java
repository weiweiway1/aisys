package com.aisys.training.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 训练任务相关 DTO（record）。JSONB 字段用 Object/String 接收；分页/详情/动作分别成组。
 */
public final class TrainingTaskDtos {

    private TrainingTaskDtos() {}

    /** 创建训练任务请求。 */
    public record Create(
            Long projectId,
            @NotBlank @Size(max = 128) String name,
            Long modelVersionId,
            Long datasetVersionId,
            String image,
            String command,
            ResourceSpec resourceSpec,
            Map<String, Object> hyperparameters,
            /** 容器描述：{imageName,imageTarRelPath,datasetRelPath,datasetFormat,taskMode}。前端选定模型版本/数据集版本后填入。 */
            Map<String, Object> containerSpec,
            Integer priority,
            Long templateId
    ) {}

    /** 优先级更新请求（范围 -100~100，业务层显式校验）。 */
    public record PriorityUpdate(@NotNull Integer priority) {}

    /** 任务详情/列表响应。 */
    public record Response(
            Long id,
            Long tenantId,
            Long projectId,
            String name,
            Long modelVersionId,
            Long datasetVersionId,
            String image,
            String command,
            ResourceSpec resourceSpec,
            Object hyperparameters,
            Object containerSpec,
            String status,
            Integer priority,
            Long assignedNodeId,
            Integer progress,
            String errorMessage,
            Instant startedAt,
            Instant completedAt,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt,
            /** 训练产物（检查点/权重文件）列表，仅在详情接口填充 */
            List<CheckpointDto> checkpoints
    ) {}

    /** 日志行响应。 */
    public record LogLine(
            Long id,
            Long taskId,
            String level,
            Long step,
            String message,
            Instant loggedAt
    ) {}

    /** 指标点响应。 */
    public record MetricPoint(
            Long id,
            Long taskId,
            Instant ts,
            Long step,
            Object metrics
    ) {}

    /** start/stop 动作响应。 */
    public record ActionResult(Long id, String status) {}
}
