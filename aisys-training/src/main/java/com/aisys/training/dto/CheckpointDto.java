package com.aisys.training.dto;

import java.time.Instant;

/**
 * checkpoint 相关 DTO（record）。
 */
public record CheckpointDto(
        Long id,
        Long taskId,
        Long step,
        String storagePath,
        Double loss,
        Object metrics,
        Boolean isActive,
        Instant createdAt
) {}
