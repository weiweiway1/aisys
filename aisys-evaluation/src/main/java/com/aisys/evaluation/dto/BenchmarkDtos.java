package com.aisys.evaluation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Benchmark 相关 DTO（record）。
 */
public final class BenchmarkDtos {

    private BenchmarkDtos() {}

    public record BenchmarkCreateRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 32) String category,
            String description,
            List<Long> datasetVersionIds,
            String metricsConfig,
            String evalConfig,
            String promptTemplate
    ) {}

    public record BenchmarkUpdateRequest(
            @Size(max = 128) String name,
            String category,
            String description,
            List<Long> datasetVersionIds,
            String metricsConfig,
            String evalConfig,
            String promptTemplate,
            String status
    ) {}

    public record BenchmarkResponse(
            Long id,
            String name,
            String category,
            String description,
            List<Long> datasetVersionIds,
            String metricsConfig,
            String evalConfig,
            String promptTemplate,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /** 单个指标条目 */
    public record MetricItem(String value, String label) {}

    /** 评测集指标配置响应（前端 leaderboard 排序下拉用） */
    public record BenchmarkMetricsResponse(
            List<MetricItem> supportedMetrics,
            String defaultSortMetric
    ) {}
}
