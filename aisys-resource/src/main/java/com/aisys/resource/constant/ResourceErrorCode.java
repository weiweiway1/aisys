package com.aisys.resource.constant;

import com.aisys.common.core.response.ErrorCode;

/**
 * Resource 服务错误码（服务号 600，6 位错误码 600xxx）。
 */
public enum ResourceErrorCode implements ErrorCode {

    NODE_NOT_FOUND(600404, "计算节点不存在"),
    NODE_OFFLINE(600410, "计算节点已离线"),
    NODE_IN_MAINTENANCE(600411, "计算节点处于维护模式"),
    AGENT_NOT_FOUND(6004041, "Agent 不存在或未注册"),
    AGENT_TOKEN_INVALID(600401, "Agent Token 无效"),
    AGENT_ID_EXISTS(600409, "Agent ID 已被占用"),
    NO_AVAILABLE_NODE(600503, "无可用计算节点满足资源需求"),
    SCHEDULE_LOCK_FAILED(6005031, "获取节点调度锁失败"),
    ALLOCATION_NOT_FOUND(6004042, "资源租约不存在"),
    HEARTBEAT_INVALID(600400, "心跳请求无效"),
    INTERNAL_ERROR(600500, "Resource 服务内部错误");

    private final int code;
    private final String message;

    ResourceErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override public int code() { return code; }
    @Override public String message() { return message; }
}
