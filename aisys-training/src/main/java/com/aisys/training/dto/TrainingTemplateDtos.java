package com.aisys.training.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/**
 * 训练模板相关 DTO（record）。
 */
public final class TrainingTemplateDtos {

    private TrainingTemplateDtos() {}

    public record Create(
            @NotBlank @Size(max = 128) String name,
            String description,
            @NotBlank String image,
            String command,
            ResourceSpec defaultResourceSpec,
            Map<String, Object> defaultHyperparameters
    ) {}

    public record Update(
            @Size(max = 128) String name,
            String description,
            String image,
            String command,
            ResourceSpec defaultResourceSpec,
            Map<String, Object> defaultHyperparameters
    ) {}

    public record Response(
            Long id,
            Long tenantId,
            String name,
            String description,
            String image,
            String command,
            ResourceSpec defaultResourceSpec,
            Object defaultHyperparameters,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /** 实例化模板为任务（仅填模板默认值，可被请求体覆盖）。 */
    public record Instantiate(
            Long projectId,
            @NotBlank @Size(max = 128) String name,
            Long modelVersionId,
            Long datasetVersionId,
            String image,
            String command,
            ResourceSpec resourceSpec,
            Map<String, Object> hyperparameters,
            Integer priority
    ) {}
}
