package com.aisys.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;

/**
 * 存储池相关 DTO（DDD 5.8.1）。
 * 全部使用 record，绝不携带 secretKey 明文回前端。
 */
public final class StoragePoolDtos {

    private StoragePoolDtos() {}

    /** 创建/更新存储池请求。 */
    public record Create(
            @NotBlank String name,
            String type,
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey,
            @PositiveOrZero Long quotaBytes,
            String status
    ) {}

    /** 存储池响应（脱敏：不含 secretKey）。 */
    public record Response(
            Long id,
            String name,
            String type,
            String endpoint,
            String bucket,
            String accessKey,
            Long quotaBytes,
            Long usedBytes,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    /** 使用量响应。 */
    public record Usage(
            Long poolId,
            String name,
            Long quotaBytes,
            Long usedBytes,
            Long freeBytes,
            double usagePercent
    ) {}
}
