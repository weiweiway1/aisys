package com.aisys.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;

/**
 * 上传任务 DTO（大文件分片：浏览器→后端→存储池；DDD 5.8）。
 */
public final class UploadTaskDtos {

    private UploadTaskDtos() {}

    /** 发起上传任务请求。 */
    public record Initiate(
            Long poolId,
            @NotBlank String path,        // 租户相对目标路径（与 browse 一致）
            String fileName,
            @Positive Long size,          // 文件总字节
            @Positive Long chunkSize      // 每片字节（>=5MB，S3 分片最小要求）
    ) {}

    /** 发起响应。 */
    public record InitiateResponse(
            Long taskId,
            Long poolId,
            String path,
            long size,
            long chunkSize,
            int totalChunks,
            String status
    ) {}

    /** 单片上传响应。 */
    public record ChunkResponse(
            int partNumber,
            String etag,
            int receivedChunks,
            int totalChunks
    ) {}

    /** 任务状态响应（列表/详情）。 */
    public record Response(
            Long id,
            Long poolId,
            String path,
            String fileName,
            long size,
            long chunkSize,
            int totalChunks,
            int receivedChunks,
            String status,        // UPLOADING / PROCESSING / COMPLETED / FAILED
            String finalKey,
            String errorMsg,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}
}
