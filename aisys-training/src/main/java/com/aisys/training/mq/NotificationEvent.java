package com.aisys.training.mq;

import com.aisys.common.mq.message.BaseMessage;

import java.util.Map;

/**
 * 通知事件（生产）。训练完成时发到 notification.event 交换机，由 notification 服务消费。
 */
public class NotificationEvent extends BaseMessage {

    private Long recipientUserId;
    private String type;            // TASK_COMPLETED / TASK_FAILED ...
    private String title;
    private String content;
    private Map<String, Object> data;

    @Override
    public String messageType() {
        return "NOTIFICATION_EVENT";
    }

    public Long getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(Long recipientUserId) { this.recipientUserId = recipientUserId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
}
