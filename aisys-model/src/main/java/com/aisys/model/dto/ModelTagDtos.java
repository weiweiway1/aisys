package com.aisys.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 模型标签相关 DTO（record）。ModelTagNode 为树形节点（含子节点递归）。
 */
public final class ModelTagDtos {

    private ModelTagDtos() {}

    public record ModelTagCreateRequest(
            Long parentId,
            @NotBlank @Size(max = 64) String name,
            Integer sort
    ) {}

    /** 树形节点（含子节点）。 */
    public record ModelTagNode(
            Long id,
            Long parentId,
            String name,
            String path,
            Integer sort,
            Instant createdAt,
            List<ModelTagNode> children
    ) {}
}
