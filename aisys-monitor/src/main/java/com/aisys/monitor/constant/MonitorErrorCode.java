package com.aisys.monitor.constant;

import com.aisys.common.core.response.ErrorCode;

/** Monitor Service 错误码（服务号 900，DDD 3.4）。 */
public enum MonitorErrorCode implements ErrorCode {
    BAD_REQUEST(900001, "请求参数错误"),
    INVALID_TIME_RANGE(900002, "时间范围无效：startTime 不能晚于 endTime"),
    INVALID_PAGE_PARAM(900003, "分页参数无效"),
    INTERNAL_ERROR(900004, "监控服务内部错误");

    private final int code;
    private final String message;

    MonitorErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override public int code() { return code; }
    @Override public String message() { return message; }
}
