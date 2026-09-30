package com.aisys.training.mq;

import com.aisys.common.mq.message.BaseMessage;

import java.time.Instant;
import java.util.List;

/**
 * 任务日志事件（消费，批量）。由 Agent 发到 task.log 交换机，training 批量写入 task_log 表。
 */
public class TaskLogEvent extends BaseMessage {

    private Long taskId;
    private List<LogEntry> entries;
    // 兼容资源侧单条扁平日志（resource TaskLogMessage 无 entries，直接发 level/message/step）。
    private String level;
    private Long step;
    private String message;
    private Instant loggedAt;

    @Override
    public String messageType() {
        return "TASK_LOG";
    }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public List<LogEntry> getEntries() { return entries; }
    public void setEntries(List<LogEntry> entries) { this.entries = entries; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Long getStep() { return step; }
    public void setStep(Long step) { this.step = step; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getLoggedAt() { return loggedAt; }
    public void setLoggedAt(Instant loggedAt) { this.loggedAt = loggedAt; }

    public static class LogEntry {
        private String level;
        private Long step;
        private String message;
        private Instant loggedAt;

        public LogEntry() {}

        public LogEntry(String level, Long step, String message, Instant loggedAt) {
            this.level = level;
            this.step = step;
            this.message = message;
            this.loggedAt = loggedAt;
        }

        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public Long getStep() { return step; }
        public void setStep(Long step) { this.step = step; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Instant getLoggedAt() { return loggedAt; }
        public void setLoggedAt(Instant loggedAt) { this.loggedAt = loggedAt; }
    }
}
