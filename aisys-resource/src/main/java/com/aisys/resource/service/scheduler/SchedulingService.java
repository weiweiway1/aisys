package com.aisys.resource.service.scheduler;

import com.aisys.resource.dto.TaskDtos.TaskCommandMessage;

/**
 * 调度服务：消费 task.command 后据此分配节点、写租约、下发 run_task 指令。
 */
public interface SchedulingService {

    /**
     * 处理一条任务命令：调度 → 写 resource_allocation（held）→ WebSocket 下发 run_task。
     *
     * @return 被调度的节点 id；null 表示无可用节点（调用方可据此回失败状态）。
     */
    Long scheduleAndDispatch(TaskCommandMessage command);
}
