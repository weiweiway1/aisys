package com.aisys.training.mapper;

import com.aisys.training.entity.TrainingTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TrainingTaskMapper {

    int insert(TrainingTask task);

    int update(TrainingTask task);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("progress") Integer progress,
                     @Param("errorMessage") String errorMessage,
                     @Param("assignedNodeId") Long assignedNodeId,
                     @Param("startedAt") java.time.Instant startedAt,
                     @Param("completedAt") java.time.Instant completedAt);

    int updatePriority(@Param("id") Long id, @Param("priority") Integer priority);

    /** 重新入队（start/resume/rollback）：硬清空上一次运行的 error_message/completed_at/assigned_node_id/started_at，progress 归零。 */
    int resetForRequeue(@Param("id") Long id);

    TrainingTask selectById(@Param("id") Long id);

    long count(@Param("tenantId") Long tenantId,
               @Param("projectId") Long projectId,
               @Param("status") String status,
               @Param("keyword") String keyword);

    List<TrainingTask> page(@Param("tenantId") Long tenantId,
                            @Param("projectId") Long projectId,
                            @Param("status") String status,
                            @Param("keyword") String keyword,
                            @Param("offset") int offset,
                            @Param("size") int size);
}
