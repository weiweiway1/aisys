package com.aisys.evaluation.client;

import com.aisys.common.core.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 数据集服务 Feign 客户端（评测侧）。仅用 ApiResponse<Map>，绝不 import dataset 服务的类。
 * 用于按版本 ID 解析数据集 storagePath（挂载给评测容器）。
 */
@FeignClient(name = "aisys-dataset", contextId = "evalDatasetClient")
public interface DatasetClient {

    /** 按版本 ID 查询数据集版本（返回 storagePath 等）。 */
    @GetMapping("/api/v1/datasets/versions/{versionId}")
    ApiResponse<Map<String, Object>> getVersionById(@PathVariable("versionId") Long versionId);
}
