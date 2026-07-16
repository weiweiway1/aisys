package com.aisys.dataset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 数据集相关 DTO（DDD 5.4.1）。record 形式，字段名与实体对齐。
 * 任务类型/格式词汇与原项目 aisys-dataset 对齐：
 * <ul>
 *   <li>taskType: image_classification / time_series / object_detection</li>
 *   <li>format（源格式）: imagenet/csv/json_manifest | ucr_ts/ucr_tsv/csv/arff | coco/yolo/voc（按 taskType）</li>
 * </ul>
 */
public final class DatasetDtos {

    private DatasetDtos() {}

    /** 创建数据集请求。 */
    public record Create(
            Long projectId,
            @NotBlank @Size(max = 256, message = "名称最长256字符") String name,
            String type,         // 兼容字段（原项目未在创建表单暴露）
            String format,       // 源格式：随 taskType（imagenet/csv/coco/yolo/voc/ucr_ts/...）
            @NotBlank @Pattern(regexp = "image_classification|time_series|object_detection",
                    message = "不支持的任务类型") String taskType,
            Long storagePoolId,
            List<String> tags,
            String description,
            String license
    ) {}

    /** 更新数据集请求（部分字段可空，null 表示不修改）。 */
    public record Update(
            @Size(max = 256, message = "名称最长256字符") String name,
            String type,
            String format,
            @Pattern(regexp = "image_classification|time_series|object_detection",
                    message = "不支持的任务类型") String taskType,
            Long storagePoolId,
            List<String> tags,
            String description,
            String license,
            String status,
            Long projectId
    ) {}

    /** 列表/详情响应。 */
    public record Response(
            Long id,
            Long tenantId,
            Long projectId,
            String name,
            String type,
            String format,
            String taskType,
            Long storagePoolId,
            Long sampleCount,
            List<String> tags,
            String description,
            String license,
            String status,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
