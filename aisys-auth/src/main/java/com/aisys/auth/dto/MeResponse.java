package com.aisys.auth.dto;

import java.time.Instant;
import java.util.List;

/** 当前用户信息（GET /me）：同时返回 roles（菜单可见性）与 permissions（按钮级 v-perms，DDD 6.1）。 */
public record MeResponse(
        Long userId,
        Long tenantId,
        String username,
        String nickname,
        String email,
        String phone,
        List<String> roles,
        List<String> permissions,
        Instant createdAt) {}
