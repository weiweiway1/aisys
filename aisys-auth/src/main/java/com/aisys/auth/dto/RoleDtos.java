package com.aisys.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 角色 CRUD / 权限分配请求体（DDD 5.7）。
 */
public final class RoleDtos {
    private RoleDtos() {}

    public record Create(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 255) String description) {}

    public record Update(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 255) String description) {}

    public record AssignPermissions(List<Long> permissionIds) {}
}
