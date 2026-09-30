package com.aisys.dataset.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

/**
 * 数据集版本相关 DTO（DDD 5.4.1）。
 */
public final class DatasetVersionDtos {

    private DatasetVersionDtos() {}

    /** 创建版本请求（先创建记录，再上传内容；或上传后回写统计）。 */
    public record Create(
            @NotBlank String version,
            String description
    ) {}

    /** 创建版本响应（含存储路径，前端据此直传 S3 或经 storage 服务）。 */
    public record CreateResponse(
            Long id,
            Long datasetId,
            String version,
            String storagePath,
            String uploadUrl,
            String status
    ) {}

    /** 版本列表/详情响应。 */
    public record Response(
            Long id,
            Long datasetId,
            String version,
            String storagePath,
            Long fileSize,
            String checksum,
            Long rowCount,
            Object columnInfo,
            String status,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt,
            // 父表 dataset 的元数据（JOIN 填充，便于评测/训练侧不经第二次查询拿到数据集名/描述/任务类型）
            String datasetName,
            String datasetDescription,
            String taskType,
            String format,
            Long sampleCount
    ) {}

    /** 预览响应：{columns, rows, total}（DDD 5.4）。 */
    public record Preview(
            List<String> columns,
            List<java.util.Map<String, Object>> rows,
            long total
    ) {}

    /**
     * 统计响应（多维，参考 refs/aisys StatisticsResponse）。
     * 旧字段 rowCount/fileSize/columnInfo/checksum 保留兼容；新增 9 维覆盖样本/文件/大小/类别/标注/划分/图像尺寸/目标尺寸。
     * 非检测/分类格式（jsonl/csv）仅 rowCount + classDistribution 有值，其余为 null（前端按需展示）。
     */
    public record Statistics(
            Long rowCount,
            Long fileSize,
            Object columnInfo,
            Object classDistribution,
            String checksum,
            Long sampleCount,
            Integer fileCount,
            Long totalSize,
            Integer classesCount,
            Long totalAnnotations,
            Double avgAnnotationsPerSample,
            Object splitDistribution,
            Object imageSizeDistribution,
            Object objectSizeDistribution
    ) {}

    /** 导出/下载响应：预签名下载 URL。 */
    public record ExportResponse(
            Long id,
            String version,
            String downloadUrl,
            long expiresInSeconds
    ) {}
}
