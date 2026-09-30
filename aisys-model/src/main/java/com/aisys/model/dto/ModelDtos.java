package com.aisys.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 模型相关 DTO（record）。请求/响应均在此处集中声明，避免散落。
 * <p>模型 type（任务类型）与数据集 taskType 完全一致：
 * image_classification(图像分类) / time_series(时序分类) / object_detection(目标检测)。
 */
public final class ModelDtos {

    private ModelDtos() {}

    // ---------- 模型 CRUD ----------

    /** 列表查询参数（绑定 GET 查询串） */
    public record ModelQueryRequest(
            String keyword,
            String status,
            Long projectId,
            Integer page,
            Integer size
    ) {}

    public record ModelCreateRequest(
            @NotBlank @Size(max = 128) String name,
            Long projectId,
            /** 任务类型：image_classification / time_series / object_detection（与数据集 taskType 一致） */
            @NotBlank(message = "模型类型不能为空")
            @Pattern(regexp = "image_classification|time_series|object_detection", message = "不支持的模型类型")
            @Size(max = 32) String type,
            @Size(max = 32) String framework,
            String description,
            /** 标签 id 列表（可选） */
            List<Long> tagIds,
            String visibility
    ) {}

    public record ModelUpdateRequest(
            @Size(max = 128) String name,
            Long projectId,
            @Pattern(regexp = "image_classification|time_series|object_detection", message = "不支持的模型类型")
            @Size(max = 32) String type,
            @Size(max = 32) String framework,
            String description,
            List<Long> tagIds,
            String visibility
    ) {}

    public record ModelListResponse(
            Long id,
            Long tenantId,
            Long projectId,
            String name,
            String type,
            String framework,
            String description,
            String status,
            String visibility,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record ModelDetailResponse(
            Long id,
            Long tenantId,
            Long projectId,
            String name,
            String type,
            String framework,
            String description,
            Object tags,
            String status,
            String visibility,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
