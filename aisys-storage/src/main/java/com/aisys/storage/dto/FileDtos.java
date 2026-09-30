package com.aisys.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 文件相关 DTO（DDD 5.8.1 / 4.2.1）。
 */
public final class FileDtos {

    private FileDtos() {}

    /** 文件浏览项。 */
    public record BrowseItem(
            String key,
            String name,
            long size,
            String contentType,
            OffsetDateTime lastModified,
            boolean directory
    ) {}

    /** 文件浏览响应。 */
    public record BrowseResult(
            Long poolId,
            String prefix,
            List<BrowseItem> items
    ) {}

    /** 预签名上传 URL 请求。 */
    public record UploadUrlRequest(
            @NotBlank String path,
            String contentType,
            @Positive Long size,
            String fileHash
    ) {}

    /** 预签名上传 URL 响应（单次直传）。 */
    public record UploadUrlResponse(
            Long poolId,
            String key,
            String uploadUrl,
            String method,
            int expiresIn
    ) {}

    /** 预签名下载 URL 请求。 */
    public record DownloadUrlResponse(
            Long poolId,
            String key,
            String downloadUrl,
            int expiresIn
    ) {}

    /** 内部上传协议 - 发起请求（秒传/分片，DDD 4.2.1）。 */
    public record InitiateRequest(
            @NotBlank String path,
            String contentType,
            @Positive Long size,
            String fileHash,
            boolean multipart,
            @Positive Integer partSize
    ) {}

    /** 分片上传的预签名 part URL。 */
    public record PresignedPart(
            int partNumber,
            String uploadUrl
    ) {}

    /** 内部上传协议 - 发起响应。 */
    public record InitiateResponse(
            String mode,                 // dedup | direct | multipart
            Long poolId,
            String key,
            String uploadId,             // 仅 multipart
            List<PresignedPart> parts,   // 仅 multipart
            String uploadUrl,            // direct 模式
            String dedupKey,             // dedup 模式命中的对象 key
            int expiresIn
    ) {}

    /** 内部上传协议 - 完成请求（分片汇总，DDD 4.2.1）。 */
    public record CompletePart(
            int partNumber,
            String etag
    ) {}

    public record CompleteRequest(
            @NotBlank String uploadId,
            @NotBlank String key,
            List<CompletePart> parts
    ) {}

    /** 内部上传协议 - 完成响应。 */
    public record CompleteResponse(
            String key,
            boolean ok
    ) {}

    /** 删除文件请求。 */
    public record DeleteRequest(
            List<String> keys
    ) {}

    /** 浏览器代理上传结果（key 为租户相对路径，与 browse 一致）。 */
    public record UploadResult(
            Long poolId,
            String key,
            long size
    ) {}
}
