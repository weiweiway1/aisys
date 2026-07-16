package com.aisys.resource.mapper;

import com.aisys.resource.entity.ResourceAllocation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ResourceAllocationMapper {

    int insert(ResourceAllocation allocation);

    /** 单节点 held 租约聚合（gpu/cpu/memory 总和、计数） */
    NodeHeldAggregate sumHeldByNode(@Param("nodeId") Long nodeId);

    /** 多节点一次性聚合（调度预过滤用），返回每节点 held 汇总 */
    List<NodeHeldAggregate> sumHeldByNodes(@Param("nodeIds") List<Long> nodeIds);

    /** 节点所有 held 租约明细（释放用） */
    List<ResourceAllocation> selectHeldByNode(@Param("nodeId") Long nodeId);

    /** 按 task 释放租约（任务结束/失败） */
    int releaseByTask(@Param("taskType") String taskType, @Param("taskId") Long taskId);

    /** 按 id 释放 */
    int releaseById(@Param("id") Long id);

    /** 释放节点全部 held 租约（节点离线回收） */
    int releaseAllHeldByNode(@Param("nodeId") Long nodeId);

    /** 统计节点 held 租约条数（getNodeMetrics） */
    int countHeldByNode(@Param("nodeId") Long nodeId);

    /** 查询单条 held 租约（按 task） */
    ResourceAllocation selectHeldByTask(@Param("taskType") String taskType, @Param("taskId") Long taskId);
}
