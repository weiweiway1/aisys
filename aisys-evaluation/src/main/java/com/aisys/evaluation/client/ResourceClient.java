package com.aisys.evaluation.client;

import com.aisys.common.core.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * resource 服务 Feign 客户端。仅用于查询节点/调度辅助信息（非必需）。
 * <p>返回类型仅用本服务自定义类型或 {@code ApiResponse<java.util.Map>}，
 * 绝不 import resource 服务的类（DDD 跨服务契约）。
 */
@FeignClient(name = "aisys-resource", contextId = "resourceClient")
public interface ResourceClient {

    /** 查询可用计算节点列表（简化：透传 Map）。 */
    @GetMapping("/api/v1/resources/nodes")
    ApiResponse<java.util.List<Map<String, Object>>> listNodes();

    /** 查询单个节点详情。 */
    @GetMapping("/api/v1/resources/nodes/{id}")
    ApiResponse<Map<String, Object>> getNode(@PathVariable("id") Long id);
}
