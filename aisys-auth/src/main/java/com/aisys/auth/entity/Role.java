package com.aisys.auth.entity;

import java.time.Instant;

public class Role {
    private Long id;
    private String code;
    private String name;
    private String description;
    private Boolean isSystem;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getIsSystem() { return isSystem; }
    public void setIsSystem(Boolean isSystem) { this.isSystem = isSystem; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
