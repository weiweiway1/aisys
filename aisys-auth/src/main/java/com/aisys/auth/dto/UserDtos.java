package com.aisys.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class UserDtos {
    private UserDtos() {}

    public record Create(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 6, max = 64) String password,
            String email,
            String nickname,
            String phone,
            List<String> roleCodes) {}

    public record Update(String email, String nickname, String phone, String status) {}

    public record Response(Long id, Long tenantId, String username, String email, String phone,
                           String nickname, String status, List<String> roles, Instant lastLoginAt, Instant createdAt) {}

    public record AssignRoles(List<Long> roleIds) {}
}
