package com.aisys.resource.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 节点分组 CRUD 请求体（DDD 5.7）。
 */
public final class NodeGroupDtos {
    private NodeGroupDtos() {}

    public record Create(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 255) String description) {}

    public record Update(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 255) String description) {}

    /** 分组响应（含节点数） */
    public record Response(
            Long id,
            String name,
            String description,
            int nodeCount,
            java.time.OffsetDateTime createdAt) {}
}
