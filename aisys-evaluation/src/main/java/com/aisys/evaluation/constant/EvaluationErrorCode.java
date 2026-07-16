package com.aisys.evaluation.constant;

import com.aisys.common.core.response.ErrorCode;

/**
 * 评测服务错误码（服务号 500）。6 位编码 SSSEEE。
 */
public enum EvaluationErrorCode implements ErrorCode {
    BENCHMARK_NOT_FOUND(500001, "评测基准不存在"),
    EVALUATION_TASK_NOT_FOUND(500002, "评测任务不存在"),
    SUBTASK_NOT_FOUND(500003, "评测子任务不存在"),
    RESULT_NOT_FOUND(500004, "评测结果不存在"),
    TASK_ALREADY_RUNNING(500005, "评测任务已在运行，无法重复启动"),
    TASK_NOT_RUNNING(500006, "评测任务不在运行状态"),
    NO_MODEL_VERSIONS(500007, "评测任务未指定任何模型版本"),
    BENCHMARK_INACTIVE(500008, "评测基准已归档，不可使用"),
    INVALID_RESULT_IDS(500009, "比对结果ID列表无效"),
    EVALUATION_INTERNAL_ERROR(500999, "评测服务内部错误");

    private final int code;
    private final String message;

    EvaluationErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override public int code() { return code; }
    @Override public String message() { return message; }
}
