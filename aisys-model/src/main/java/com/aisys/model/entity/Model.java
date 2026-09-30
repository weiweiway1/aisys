package com.aisys.model.entity;

import java.time.Instant;

/**
 * 模型实体（表 model，RLS 租户隔离）。普通 class + getter/setter（DDD 约定，不用 Lombok）。
 */
public class Model {

    private Long id;
    private Long tenantId;
    private Long projectId;
    private String name;
    private String type;
    private String framework;
    private String description;
    /** JSONB → 以字符串形式持有，序列化由调用方负责 */
    private String tags;
    private String status;
    private String visibility;
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getFramework() { return framework; }
    public void setFramework(String framework) { this.framework = framework; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
