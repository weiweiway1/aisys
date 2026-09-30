package com.aisys.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 模型版本 / 分片上传相关 DTO（record）。
 */
public final class VersionDtos {

    private VersionDtos() {}

    // ---------- 版本 ----------

    public record ModelVersionCreateRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]{1,64}",
                    message = "版本号仅允许字母、数字、点、下划线、短横线") String version,
            /** 版本配置（对象）：如 {imageName, framework, taskType}。服务端序列化为 JSONB 存储。 */
            Object config
    ) {}

    public record ModelVersionResponse(
            Long id,
            Long modelId,
            String version,
            String storagePath,
            Long fileSize,
            String checksum,
            String status,
            Object config,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt,
            // 父表 model 的元数据（JOIN 填充，便于评测/训练侧不经第二次查询拿到模型名/描述/任务类型）
            String modelName,
            String modelDescription,
            String taskType,
            String framework
    ) {}

    // ---------- 分片上传 ----------

    public record UploadInitRequest(
            @NotNull Long fileSize,
            /** 文件 hash（用于秒传比对） */
            String fileHash,
            /** 期望的分片大小（字节）；为空则用服务默认值 */
            Long chunkSize
    ) {}

    /** 单个分片的预签名上传地址 */
    public record ChunkUrl(int partNumber, String url) {}

    public record UploadInitResponse(
            String uploadId,
            String storagePath,
            int chunkCount,
            long chunkSize,
            List<ChunkUrl> chunkUrls,
            /** true 表示命中秒传（同 checksum 已存在），客户端无需再上传 */
            boolean dedup
    ) {}

    /** 上传完成的单个分片回执（partNumber 与 S3 ETag） */
    public record CompletedPart(int partNumber, String etag) {}

    public record UploadCompleteRequest(
            String uploadId,
            @NotNull Long fileSize,
            String checksum,
            List<CompletedPart> parts
    ) {}

    public record UploadCompleteResponse(
            Long versionId,
            String storagePath,
            Long fileSize,
            String checksum,
            String status
    ) {}
}
