package com.aisys.evaluation.client;

import com.aisys.common.core.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 模型服务 Feign 客户端（评测侧）。仅用 ApiResponse<Map>，绝不 import model 服务的类（跨服务契约）。
 * 用于按版本 ID 解析模型镜像 tar 路径 + config.imageName。
 */
@FeignClient(name = "aisys-model", contextId = "evalModelClient")
public interface ModelClient {

    /** 按版本 ID 查询模型版本（返回 storagePath + config 等）。 */
    @GetMapping("/api/v1/models/versions/by-id/{versionId}")
    ApiResponse<Map<String, Object>> getVersionById(@PathVariable("versionId") Long versionId);
}
