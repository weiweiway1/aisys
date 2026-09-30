package com.aisys.training.dto;

/**
 * 资源规格（resource_spec JSONB 的 Java 投影）。
 * <p>跨服务透传给 Resource 服务调度；字段对齐 DDD 5.5.1。
 */
public record ResourceSpec(
        Integer gpuCount,
        Integer cpu,
        Long memoryBytes,
        String gpuType
) {
    public ResourceSpec {
        if (gpuCount == null) gpuCount = 0;
        if (cpu == null) cpu = 0;
    }
}
