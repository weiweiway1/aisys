package com.aisys.evaluation.mapper;

import com.aisys.evaluation.entity.EvaluationSubtask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评测子任务 Mapper。
 */
@Mapper
public interface EvaluationSubtaskMapper {

    void insert(EvaluationSubtask subtask);

    void batchInsert(@Param("list") List<EvaluationSubtask> list);

    EvaluationSubtask selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);

    List<EvaluationSubtask> selectByParentTaskId(@Param("parentTaskId") Long parentTaskId,
                                                  @Param("tenantId") Long tenantId);

    /**
     * 按 model_version_id 查找子任务（消费 task.status 时定位，resource 回写）。
     */
    EvaluationSubtask selectByParentAndModel(@Param("parentTaskId") Long parentTaskId,
                                             @Param("modelVersionId") Long modelVersionId,
                                             @Param("tenantId") Long tenantId);

    int updateStatus(@Param("id") Long id,
                     @Param("tenantId") Long tenantId,
                     @Param("status") String status,
                     @Param("assignedNodeId") Long assignedNodeId,
                     @Param("errorMessage") String errorMessage,
                     @Param("startedAt") java.time.Instant startedAt,
                     @Param("completedAt") java.time.Instant completedAt);

    /**
     * 统计父任务下各状态子任务数。
     */
    List<StatusCount> countByStatus(@Param("parentTaskId") Long parentTaskId,
                                    @Param("tenantId") Long tenantId);

    /** 状态计数行（status -> cnt）。 */
    class StatusCount {
        private String status;
        private Long cnt;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getCnt() { return cnt; }
        public void setCnt(Long cnt) { this.cnt = cnt; }
    }

    int cancelByParentTaskId(@Param("parentTaskId") Long parentTaskId,
                             @Param("tenantId") Long tenantId);
}
