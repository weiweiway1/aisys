package com.aisys.evaluation.entity;

import java.time.Instant;

/**
 * 评测基准集（DDD 5.6.1）。JSONB 列以原始 JSON 字符串承载，由 Service 层用 ObjectMapper 转换。
 */
public class Benchmark {

    private Long id;
    private Long tenantId;
    private String name;
    private String category;
    private String description;
    /** dataset_version_ids JSONB → 原始 JSON 字符串（如 "[1,2,3]"） */
    private String datasetVersionIds;
    /** metrics_config JSONB → 原始 JSON 字符串 */
    private String metricsConfig;
    private String evalConfig;
    private String promptTemplate;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDatasetVersionIds() { return datasetVersionIds; }
    public void setDatasetVersionIds(String datasetVersionIds) { this.datasetVersionIds = datasetVersionIds; }
    public String getMetricsConfig() { return metricsConfig; }
    public void setMetricsConfig(String metricsConfig) { this.metricsConfig = metricsConfig; }
    public String getEvalConfig() { return evalConfig; }
    public void setEvalConfig(String evalConfig) { this.evalConfig = evalConfig; }
    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
