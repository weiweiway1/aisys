package com.aisys.resource.service.scheduler;

import com.aisys.resource.dto.TaskDtos.TaskResourceSpec;
import com.aisys.resource.service.impl.NodeInfoParser.FreeCapacity;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 最优适应策略（DDD 5.7.3）：选择"刚好满足需求"的节点以减少碎片。
 * <ul>
 *   <li>GPU 任务（task.gpuCount &gt; 0）：主键空闲 GPU 数升序 → 空闲内存升序 → 节点 id（稳定）。</li>
 *   <li>CPU-only 任务（task.gpuCount == 0）：空闲内存升序 → 节点 id（不按 GPU 数比较）。</li>
 * </ul>
 * 候选集已由调度器过滤（满足资源量 + 标签），这里只排序选最优。
 */
@Component
public class BestFitStrategy implements SchedulingStrategy {

    @Override
    public ScoredNode schedule(TaskResourceSpec task, List<ScoredNode> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;

        Comparator<ScoredNode> byGpu = Comparator.comparingLong(s -> s.free().freeGpu());
        Comparator<ScoredNode> byMem = Comparator.comparingLong(s -> s.free().freeMemory());
        Comparator<ScoredNode> byId  = Comparator.comparingLong(ScoredNode::nodeId);

        Comparator<ScoredNode> cmp = (task.gpuCount() == 0)
                ? byMem.thenComparing(byId)
                : byGpu.thenComparing(byMem).thenComparing(byId);

        return candidates.stream().min(cmp).orElse(null);
    }
}
