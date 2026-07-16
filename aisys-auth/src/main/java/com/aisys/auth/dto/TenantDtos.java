package com.aisys.auth.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class TenantDtos {
    private TenantDtos() {}

    public record Create(@NotBlank String name, @NotBlank String code, Long maxQuotaBytes, String status) {}

    public record Response(Long id, String name, String code, String status,
                           Long storagePoolId, Long maxQuotaBytes, Instant createdAt) {}
}
