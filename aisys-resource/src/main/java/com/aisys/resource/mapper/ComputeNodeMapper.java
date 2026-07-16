package com.aisys.resource.mapper;

import com.aisys.resource.entity.ComputeNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ComputeNodeMapper {

    ComputeNode selectById(@Param("id") Long id);

    ComputeNode selectByAgentId(@Param("agentId") String agentId);

    List<ComputeNode> page(@Param("status") String status,
                           @Param("keyword") String keyword,
                           @Param("groupId") Long groupId,
                           @Param("offset") int offset,
                           @Param("size") int size);

    long count(@Param("status") String status,
               @Param("keyword") String keyword,
               @Param("groupId") Long groupId);

    /** 候选调度节点：status=online（不含 maintenance） */
    List<ComputeNode> selectOnlineCandidates();

    int insert(ComputeNode node);

    int update(ComputeNode node);

    int deleteById(@Param("id") Long id);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int updateLabels(@Param("id") Long id, @Param("labels") String labelsJson);

    /** 显式更新 node_group_id（null 表示清除分组 —— 不受 <set>/<if> 跳过 null 影响） */
    int updateNodeGroup(@Param("id") Long id, @Param("nodeGroupId") Long nodeGroupId);

    /** 心跳更新：刷新心跳时间 + 在线状态 + 运行中任务数（负载信号） */
    int updateHeartbeat(@Param("id") Long id, @Param("now") OffsetDateTime now, @Param("runningTasks") Integer runningTasks);

    /** 仅更新运行中任务数（负载快照） */
    int updateLoad(@Param("id") Long id, @Param("runningTasks") Integer runningTasks);

    /** 心跳超时检测：status=online 且 last_heartbeat_at 早于 cutoff（或为空） */
    List<ComputeNode> selectHeartbeatTimeout(@Param("cutoff") OffsetDateTime cutoff);

    int updateStatusByAgentId(@Param("agentId") String agentId, @Param("status") String status);
}
