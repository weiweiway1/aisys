package com.aisys.resource.dto;

import java.util.Map;

/**
 * WebSocket 双向消息的统一信封（平台↔Agent）。
 * <p>type 取值见 {@link com.aisys.resource.constant.ResourceConstants}：
 * <ul>
 *   <li>平台→Agent：run_task / stop_task / ping</li>
 *   <li>Agent→平台：status / log / metrics / result</li>
 * </ul>
 */
public record WebSocketMessage(
        String type,
        String agentId,
        Long taskId,
        String taskType,
        Long tenantId,
        String level,            // log: INFO/WARN/ERROR
        String message,          // log/status 消息文本
        Long timestamp,          // 毫秒
        Map<String, Object> metrics,   // metrics 上报数值
        Map<String, Object> data       // 通用扩展字段
) {
    public static WebSocketMessage of(String type, Long taskId, String taskType, Long tenantId, Map<String, Object> data) {
        return new WebSocketMessage(type, null, taskId, taskType, tenantId, null, null,
                System.currentTimeMillis(), null, data);
    }
}
