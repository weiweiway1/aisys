package com.aisys.training.mq;

import com.aisys.common.mq.message.BaseMessage;

import java.util.Map;

/**
 * 任务调度命令（DDD 5.5.1）。由 training 生产到 task.command 交换机，Resource 服务消费并执行调度。
 * <p>路由键：task.command.train.{taskId}
 */
public class TaskCommandMessage extends BaseMessage {

    private Long taskId;
    private String taskType;          // 固定 "TRAINING"
    private ResourceSpecPayload resourceSpec;
    private String image;
    private String command;
    private Map<String, String> env;
    private Integer priority;
    // ——「每个模型是一个容器」：镜像 tar + 数据集以 relPath 下发，Resource 用 tenantId 拼全 key 预签名 ——
    private String imageName;         // docker load 后 run 的镜像名，如 aisys/yolo-demo:cpu
    private String imageTarRelPath;   // 模型版本存储路径（relPath）
    private String datasetRelPath;    // 数据集版本存储路径（relPath）
    private String datasetFormat;     // yolo / coco / csv ...
    private String taskMode;          // train / eval

    @Override
    public String messageType() {
        return "TASK_COMMAND_TRAINING";
    }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public ResourceSpecPayload getResourceSpec() { return resourceSpec; }
    public void setResourceSpec(ResourceSpecPayload resourceSpec) { this.resourceSpec = resourceSpec; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public String getImageName() { return imageName; }
    public void setImageName(String imageName) { this.imageName = imageName; }
    public String getImageTarRelPath() { return imageTarRelPath; }
    public void setImageTarRelPath(String imageTarRelPath) { this.imageTarRelPath = imageTarRelPath; }
    public String getDatasetRelPath() { return datasetRelPath; }
    public void setDatasetRelPath(String datasetRelPath) { this.datasetRelPath = datasetRelPath; }
    public String getDatasetFormat() { return datasetFormat; }
    public void setDatasetFormat(String datasetFormat) { this.datasetFormat = datasetFormat; }
    public String getTaskMode() { return taskMode; }
    public void setTaskMode(String taskMode) { this.taskMode = taskMode; }

    /** 资源规格载荷（跨服务透传，独立于 dto.ResourceSpec 以避免序列化耦合）。 */
    public static class ResourceSpecPayload {
        private Integer gpuCount;
        private Integer cpu;
        private Long memoryBytes;
        private String gpuType;

        public ResourceSpecPayload() {}

        public ResourceSpecPayload(Integer gpuCount, Integer cpu, Long memoryBytes, String gpuType) {
            this.gpuCount = gpuCount;
            this.cpu = cpu;
            this.memoryBytes = memoryBytes;
            this.gpuType = gpuType;
        }

        public Integer getGpuCount() { return gpuCount; }
        public void setGpuCount(Integer gpuCount) { this.gpuCount = gpuCount; }
        public Integer getCpu() { return cpu; }
        public void setCpu(Integer cpu) { this.cpu = cpu; }
        public Long getMemoryBytes() { return memoryBytes; }
        public void setMemoryBytes(Long memoryBytes) { this.memoryBytes = memoryBytes; }
        public String getGpuType() { return gpuType; }
        public void setGpuType(String gpuType) { this.gpuType = gpuType; }
    }
}
