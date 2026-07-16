package com.aisys.notification.constant;

import com.aisys.common.core.response.ErrorCode;

/**
 * Notification Service 错误码（服务号 800，DDD 3.4 / 5.9）。
 */
public enum NotificationErrorCode implements ErrorCode {
    RULE_NOT_FOUND(800001, "通知规则不存在"),
    RULE_DUPLICATE(800002, "同事件类型的规则已存在"),
    RULE_TARGET_INVALID(800003, "规则目标（target_type/target_ids）无效"),
    RULE_CHANNELS_EMPTY(800004, "分发渠道不能为空"),
    NOTIFICATION_NOT_FOUND(800005, "通知不存在"),
    NO_PERMISSION(800006, "无操作权限"),
    BAD_REQUEST(800007, "请求参数错误"),
    WEBSOCKET_AUTH_FAILED(800008, "WebSocket 鉴权失败：accessToken 无效或缺失");

    private final int code;
    private final String message;

    NotificationErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override public int code() { return code; }
    @Override public String message() { return message; }
}
