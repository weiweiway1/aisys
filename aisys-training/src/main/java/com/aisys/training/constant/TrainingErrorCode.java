package com.aisys.training.constant;

import com.aisys.common.core.response.ErrorCode;

/**
 * 训练服务错误码（服务号 400）：SSSEEE → 400xxx。
 */
public enum TrainingErrorCode implements ErrorCode {
    TASK_NOT_FOUND(400404, "训练任务不存在"),
    TEMPLATE_NOT_FOUND(400405, "训练模板不存在"),
    CHECKPOINT_NOT_FOUND(400406, "checkpoint 不存在"),
    INVALID_STATUS_TRANSITION(400401, "非法的任务状态变更"),
    TASK_NOT_RUNNING(400402, "任务非运行状态"),
    TASK_ALREADY_RUNNING(400403, "任务已在运行"),
    INVALID_PRIORITY(400407, "优先级取值非法"),
    TEMPLATE_INSTANTIATE_FAILED(400408, "模板实例化失败"),
    SCHEDULE_FAILED(400503, "调度请求失败");

    private final int code;
    private final String message;

    TrainingErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override public int code() { return code; }
    @Override public String message() { return message; }
}
