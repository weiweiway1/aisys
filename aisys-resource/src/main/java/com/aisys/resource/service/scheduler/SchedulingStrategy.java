package com.aisys.resource.service.scheduler;

import com.aisys.resource.dto.TaskDtos.TaskResourceSpec;
import com.aisys.resource.entity.ComputeNode;
import com.aisys.resource.service.impl.NodeInfoParser.FreeCapacity;

import java.util.List;

/**
 * 调度策略（DDD 5.7.3）。从候选节点中选择最适合的节点。
 */
public interface SchedulingStrategy {

    /**
     * @param task       需求规格
     * @param candidates 在线且资源充足的候选节点（含空闲量快照）
     * @return 选中的节点，null 表示无可用节点
     */
    ScoredNode schedule(TaskResourceSpec task, List<ScoredNode> candidates);

    /** 候选节点 + 其空闲量快照（调度器预过滤后传入） */
    record ScoredNode(ComputeNode node, FreeCapacity free) {
        public Long nodeId() { return node.getId(); }
    }
}
