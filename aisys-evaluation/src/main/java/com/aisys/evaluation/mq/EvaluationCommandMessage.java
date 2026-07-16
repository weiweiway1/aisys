package com.aisys.evaluation.mq;

import com.aisys.common.mq.message.BaseMessage;

import java.util.List;
import java.util.Map;

/**
 * 评测任务下发指令（生产 → task.command exchange）。
 * resource 服务消费后调度执行评测，并回写 task.status。
 */
public class EvaluationCommandMessage extends BaseMessage {

    public static final String TYPE = "EVALUATION_COMMAND";

    private Long taskId;             // 父 evaluation_task.id
    private Long subtaskId;          // 子任务 id（每模型一个）
    private Long benchmarkId;
    private Long modelVersionId;
    private List<Long> datasetVersionIds;
    private String promptTemplate;
    private String metricsConfig;    // JSON 字符串
    private String evalConfig;       // JSON 字符串
    private Map<String, Object> params;
    // ——「每个模型是一个容器」：评测同样以容器方式执行（与训练同一套机制）——
    private String taskType = "EVALUATION";
    private Long tenantId;
    private String imageName;         // docker load 后 run 的镜像名
    private String imageTarRelPath;   // 模型版本存储路径（relPath）
    private String datasetRelPath;    // 数据集版本存储路径（relPath）
    private String datasetFormat;     // yolo/coco/csv...
    private String taskMode = "eval"; // 容器入口脚本走 eval 分支

    @Override
    public String messageType() {
        return TYPE;
    }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getSubtaskId() { return subtaskId; }
    public void setSubtaskId(Long subtaskId) { this.subtaskId = subtaskId; }
    public Long getBenchmarkId() { return benchmarkId; }
    public void setBenchmarkId(Long benchmarkId) { this.benchmarkId = benchmarkId; }
    public Long getModelVersionId() { return modelVersionId; }
    public void setModelVersionId(Long modelVersionId) { this.modelVersionId = modelVersionId; }
    public List<Long> getDatasetVersionIds() { return datasetVersionIds; }
    public void setDatasetVersionIds(List<Long> datasetVersionIds) { this.datasetVersionIds = datasetVersionIds; }
    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }
    public String getMetricsConfig() { return metricsConfig; }
    public void setMetricsConfig(String metricsConfig) { this.metricsConfig = metricsConfig; }
    public String getEvalConfig() { return evalConfig; }
    public void setEvalConfig(String evalConfig) { this.evalConfig = evalConfig; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
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
}
