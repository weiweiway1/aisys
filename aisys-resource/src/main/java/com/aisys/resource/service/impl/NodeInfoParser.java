package com.aisys.resource.service.impl;

import com.aisys.resource.entity.ComputeNode;
import com.aisys.resource.mapper.NodeHeldAggregate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 compute_node 的 JSONB 字段（cpu_info / gpu_info / labels）为强类型结构，
 * 并结合 held 聚合计算空闲量。集中在此避免各处散落 ObjectMapper 调用。
 */
@Component
public class NodeInfoParser {

    private final ObjectMapper objectMapper;

    public NodeInfoParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** GPU 总数 = gpu_info 数组长度 */
    public int totalGpu(ComputeNode node) {
        JsonNode arr = parse(node.getGpuInfo());
        return (arr != null && arr.isArray()) ? arr.size() : 0;
    }

    /** CPU 核数 = cpu_info.cores */
    public int totalCpu(ComputeNode node) {
        JsonNode cpu = parse(node.getCpuInfo());
        if (cpu != null && cpu.has("cores")) {
            return cpu.get("cores").asInt(0);
        }
        return 0;
    }

    public long totalMemory(ComputeNode node) {
        return node.getTotalMemory() == null ? 0L : node.getTotalMemory();
    }

    /** 标签 → Map（解析 labels JSON） */
    @SuppressWarnings("unchecked")
    public Map<String, String> labels(ComputeNode node) {
        JsonNode n = parse(node.getLabels());
        if (n == null || !n.isObject()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        n.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText("")));
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> cpuInfoMap(ComputeNode node) {
        JsonNode n = parse(node.getCpuInfo());
        return n == null ? Map.of() : objectMapper.convertValue(n, Map.class);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> gpuInfoList(ComputeNode node) {
        JsonNode n = parse(node.getGpuInfo());
        if (n == null || !n.isArray()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : n) {
            out.add(objectMapper.convertValue(item, Map.class));
        }
        return out;
    }

    public FreeCapacity freeCapacity(ComputeNode node, NodeHeldAggregate held) {
        int totalGpu = totalGpu(node);
        int totalCpu = totalCpu(node);
        long totalMem = totalMemory(node);
        long heldGpu = held == null ? 0 : held.gpuOrZero();
        long heldCpu = held == null ? 0 : held.cpuOrZero();
        long heldMem = held == null ? 0 : held.memOrZero();
        return new FreeCapacity(
                Math.max(0, totalGpu - (int) heldGpu),
                Math.max(0, totalCpu - (int) heldCpu),
                Math.max(0L, totalMem - heldMem),
                totalGpu, totalCpu, totalMem,
                (int) heldGpu, (int) heldCpu, heldMem,
                held == null ? 0 : held.cntOrZero());
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 空闲量 + 已分配量快照 */
    public record FreeCapacity(
            int freeGpu, int freeCpu, long freeMemory,
            int totalGpu, int totalCpu, long totalMemory,
            int allocatedGpu, int allocatedCpu, long allocatedMemory,
            int heldAllocations) {}
}
