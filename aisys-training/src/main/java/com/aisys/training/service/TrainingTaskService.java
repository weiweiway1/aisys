package com.aisys.training.service;

import com.aisys.training.dto.CheckpointDto;
import com.aisys.training.dto.TrainingTaskDtos;
import com.aisys.common.core.response.PageResult;

import java.time.Instant;

/**
 * 训练任务领域服务（DDD 5.5）。覆盖任务 CRUD、生命周期动作、日志/指标/checkpoint 查询、回滚。
 */
public interface TrainingTaskService {

    /** 分页查询任务列表。 */
    PageResult<TrainingTaskDtos.Response> list(Long projectId, String status, String keyword, int page, int size);

    /** 任务详情。 */
    TrainingTaskDtos.Response get(Long id);

    /** 创建任务（status=pending）。 */
    TrainingTaskDtos.Response create(TrainingTaskDtos.Create req);

    /** 启动任务（pending→queued，发 task.command 事件）。 */
    TrainingTaskDtos.Response start(Long id);

    /** 停止任务（running/paused/queued→cancelled，发 task.command 取消指令）。 */
    TrainingTaskDtos.Response stop(Long id);

    /** 暂停任务（running→paused，落 checkpoint）。 */
    TrainingTaskDtos.Response pause(Long id);

    /** 恢复任务（paused→queued，基于 checkpoint 重新调度）。 */
    TrainingTaskDtos.Response resume(Long id);

    /** 更新优先级。 */
    TrainingTaskDtos.Response updatePriority(Long id, Integer priority);

    /** 分页查询任务日志。 */
    PageResult<TrainingTaskDtos.LogLine> logs(Long id, int page, int size);

    /** 按时间范围查询任务指标。 */
    PageResult<TrainingTaskDtos.MetricPoint> metrics(Long id, Instant from, Instant to, int page, int size);

    /** 列出任务的 checkpoint。 */
    java.util.List<CheckpointDto> checkpoints(Long id);

    /** 回滚到指定 checkpoint（置 active，重新调度）。 */
    TrainingTaskDtos.Response rollback(Long id, Long checkpointId);
}
