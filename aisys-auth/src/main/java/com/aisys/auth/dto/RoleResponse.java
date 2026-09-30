package com.aisys.auth.dto;

import java.util.List;

public record RoleResponse(Long id, String code, String name, String description,
                           boolean isSystem, List<Long> permissionIds) {}
