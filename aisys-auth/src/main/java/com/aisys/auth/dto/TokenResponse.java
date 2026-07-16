package com.aisys.auth.dto;

import java.util.List;

/**
 * 登录/刷新返回。expiresIn 单位为秒（前端据此换算 expires，DDD 6.2）。
 * 一次登录即返回 roles/permissions/username/nickname，供前端 pure-admin 单次建会话所需。
 */
public record TokenResponse(String accessToken, String refreshToken, long expiresIn,
                            Long userId, String username, String nickname,
                            List<String> roles, List<String> permissions) {}
