package com.aisys.evaluation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 评测任务相关 DTO（record）。
 */
public final class EvaluationTaskDtos {

    private EvaluationTaskDtos() {}

    public record EvaluationTaskCreateRequest(
            Long projectId,
            @NotNull Long benchmarkId,
            @Size(max = 128) String name,
            @NotEmpty List<Long> modelVersionIds,
            String config
    ) {}

    public record EvaluationTaskResponse(
            Long id,
            Long projectId,
            Long benchmarkId,
            String benchmarkName,
            String name,
            List<Long> modelVersionIds,
            String status,
            Integer progress,
            String config,
            Instant startedAt,
            Instant completedAt,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt,
            List<SubtaskSummary> subtasks
    ) {}

    public record SubtaskSummary(
            Long id,
            Long modelVersionId,
            String status,
            Long assignedNodeId,
            String errorMessage,
            Instant startedAt,
            Instant completedAt
    ) {}
}
