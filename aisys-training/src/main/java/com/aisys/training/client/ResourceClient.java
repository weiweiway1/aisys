package com.aisys.training.client;

import com.aisys.common.core.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * Resource 服务 Feign 客户端（DDD 5.5.3）。
 * <p>训练服务调用 Resource 查询节点资源占用，辅助优先级调度。返回类型仅用平台公共响应容器，
 * 不引入 Resource 服务的领域类（解耦约束，DDD common-feign 约定）。
 */
@FeignClient(name = "aisys-resource", contextId = "resourceClient")
public interface ResourceClient {

    /** 查询节点资源视图（free/total gpu/cpu/mem）。 */
    @GetMapping("/api/v1/resources/nodes/{nodeId}")
    ApiResponse<Map<String, Object>> getNode(@PathVariable("nodeId") Long nodeId);
}
