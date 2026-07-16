package com.aisys.resource.service;

import com.aisys.resource.dto.NodeDtos;
import com.aisys.common.core.response.PageResult;

import java.util.Map;

public interface ComputeNodeService {

    PageResult<NodeDtos.NodeResponse> listNodes(NodeDtos.NodeQueryRequest query, int page, int size);

    NodeDtos.NodeDetailResponse getNodeDetail(Long id);

    void updateLabels(Long id, Map<String, String> labels);

    void enterMaintenance(Long id);

    void exitMaintenance(Long id);

    NodeDtos.NodeMetricsResponse getNodeMetrics(Long id);

    void deleteNode(Long id);

    void updateNode(Long id, Long nodeGroupId);

    /** 在节点上执行远程命令，返回 {exitCode, stdout, stderr, timedOut} */
    Map<String, Object> executeCommand(Long id, String command, Integer timeoutSec);
}
