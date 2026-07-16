package com.aisys.training.entity;

import java.time.Instant;

/** 训练模板实体（training_template，租户隔离）。 */
public class TrainingTemplate {
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private String image;
    private String command;
    private String defaultResourceSpec;
    private String defaultHyperparameters;
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getDefaultResourceSpec() { return defaultResourceSpec; }
    public void setDefaultResourceSpec(String defaultResourceSpec) { this.defaultResourceSpec = defaultResourceSpec; }
    public String getDefaultHyperparameters() { return defaultHyperparameters; }
    public void setDefaultHyperparameters(String defaultHyperparameters) { this.defaultHyperparameters = defaultHyperparameters; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
