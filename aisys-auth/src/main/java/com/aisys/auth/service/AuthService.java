package com.aisys.auth.service;

import com.aisys.auth.constant.AuthErrorCode;
import com.aisys.auth.dto.*;
import com.aisys.auth.entity.Role;
import com.aisys.auth.entity.User;
import com.aisys.auth.mapper.RoleMapper;
import com.aisys.auth.mapper.UserMapper;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.jwt.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 认证服务：登录/注册/刷新/登出/me/改密（DDD 5.2）。
 * <p>JWT RS256 签发；Refresh Token 存 Redis（key=auth:refresh:{userId}，TTL=7d），
 * 刷新即轮换 + 复用检测（DDD 4.1.1）；登出将 Access Token 的 jti 加入黑名单。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String REFRESH_KEY = "auth:refresh:";
    private static final String BLACKLIST_KEY = "auth:token:blacklist:";

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public AuthService(UserMapper userMapper, RoleMapper roleMapper, JwtUtil jwtUtil, StringRedisTemplate redis) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.jwtUtil = jwtUtil;
        this.redis = redis;
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        User user = userMapper.selectByUsername(req.username());
        log.info("[login] 尝试登录 username=[{}] userFound={}", req.username(), user != null);
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            log.warn("[login] 认证失败 username=[{}] found={} pwLen={}",
                    req.username(), user != null, req.password() == null ? -1 : req.password().length());
            throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        if (!"active".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(AuthErrorCode.USER_DISABLED);
        }
        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        List<String> permissions = userMapper.selectPermissionCodesByUserId(user.getId());
        JwtUtil.IssuedToken access = jwtUtil.generateAccessToken(user.getId(), user.getTenantId(), roles, user.getUsername());
        JwtUtil.IssuedToken refresh = jwtUtil.generateRefreshToken(user.getId(), user.getTenantId(), roles, user.getUsername());

        // 存储 refresh（轮换：替换旧值），TTL = refresh 有效期
        redis.opsForValue().set(REFRESH_KEY + user.getId(), refresh.token(),
                Duration.ofSeconds(jwtUtil.refreshTtlSeconds()));

        userMapper.updateLastLogin(user.getId());
        log.info("用户登录成功 userId={} username={}", user.getId(), user.getUsername());
        return new TokenResponse(access.token(), refresh.token(), access.ttlSeconds(),
                user.getId(), user.getUsername(), user.getNickname(), roles, permissions);
    }

    public TokenResponse refresh(RefreshRequest req) {
        JwtUtil.ParsedToken parsed;
        try {
            parsed = jwtUtil.parse(req.refreshToken());
        } catch (Exception e) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        if (!parsed.isRefreshToken()) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        Long userId = parsed.userId();
        String stored = redis.opsForValue().get(REFRESH_KEY + userId);
        if (stored == null) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        // 复用检测：提交的 refresh 与 Redis 中不一致 → 令牌泄露，吊销全部
        if (!stored.equals(req.refreshToken())) {
            log.warn("检测到 Refresh Token 复用 userId={}", userId);
            redis.delete(REFRESH_KEY + userId);
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
        }
        // 轮换：刷新前校验用户仍然存在且启用（禁用/删除的用户不得换发新令牌）
        User u = userMapper.selectById(userId);
        if (u == null || !"active".equalsIgnoreCase(u.getStatus())) {
            redis.delete(REFRESH_KEY + userId);
            throw new BusinessException(AuthErrorCode.USER_DISABLED);
        }
        List<String> roles = userMapper.selectRoleCodesByUserId(userId);
        List<String> permissions = userMapper.selectPermissionCodesByUserId(userId);
        JwtUtil.IssuedToken access = jwtUtil.generateAccessToken(userId, parsed.tenantId(), roles, u.getUsername());
        JwtUtil.IssuedToken newRefresh = jwtUtil.generateRefreshToken(userId, parsed.tenantId(), roles, u.getUsername());
        redis.opsForValue().set(REFRESH_KEY + userId, newRefresh.token(),
                Duration.ofSeconds(jwtUtil.refreshTtlSeconds()));
        return new TokenResponse(access.token(), newRefresh.token(), access.ttlSeconds(), userId,
                u.getUsername(), u.getNickname(), roles, permissions);
    }

    @Transactional
    public void logout(String accessToken, Long userId) {
        try {
            JwtUtil.ParsedToken parsed = jwtUtil.parse(accessToken);
            // 将 access jti 加入黑名单，TTL = 剩余有效期
            long remaining = (parsed.expiration().getTime() - System.currentTimeMillis()) / 1000;
            if (remaining > 0) {
                redis.opsForValue().set(BLACKLIST_KEY + parsed.jti(), "1", Duration.ofSeconds(remaining));
            }
        } catch (Exception ignored) {
            // access token 无效则不处理黑名单
        }
        if (userId != null) {
            redis.delete(REFRESH_KEY + userId);
        }
    }

    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(AuthErrorCode.USER_NOT_FOUND);
        }
        List<String> roles = userMapper.selectRoleCodesByUserId(userId);
        List<String> permissions = userMapper.selectPermissionCodesByUserId(userId);
        return new MeResponse(user.getId(), user.getTenantId(), user.getUsername(),
                user.getNickname(), user.getEmail(), user.getPhone(), roles, permissions, user.getCreatedAt());
    }

    @Transactional
    public void changePassword(Long userId, PasswordChangeRequest req, String accessToken) {
        User user = userMapper.selectById(userId);
        if (user == null || !passwordEncoder.matches(req.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException(AuthErrorCode.OLD_PASSWORD_WRONG);
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(req.newPassword()));
        // 改密后使 refresh 失效，要求重新登录
        redis.delete(REFRESH_KEY + userId);
        // 并将当前 access token 的 jti 加入黑名单，防止旧令牌在 TTL（最长 24h）内继续可用
        if (accessToken != null) {
            try {
                JwtUtil.ParsedToken parsed = jwtUtil.parse(accessToken);
                long remaining = (parsed.expiration().getTime() - System.currentTimeMillis()) / 1000;
                if (remaining > 0) {
                    redis.opsForValue().set(BLACKLIST_KEY + parsed.jti(), "1", Duration.ofSeconds(remaining));
                }
            } catch (Exception ignored) {
                // access token 无法解析则不处理黑名单
            }
        }
    }

    /** 当前用户自助更新资料（昵称/邮箱/电话）。 */
    @Transactional
    public void updateProfile(Long userId, ProfileUpdateRequest req) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(AuthErrorCode.USER_NOT_FOUND);
        }
        if (req.nickname() != null) user.setNickname(req.nickname());
        if (req.email() != null) user.setEmail(req.email());
        if (req.phone() != null) user.setPhone(req.phone());
        userMapper.update(user);
    }

    @Transactional
    public Long register(RegisterRequest req) {
        if (userMapper.selectByUsername(req.username()) != null) {
            throw new BusinessException(AuthErrorCode.USERNAME_EXISTS);
        }
        User user = new User();
        user.setUsername(req.username());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setEmail(req.email());
        user.setNickname(req.nickname());
        user.setStatus("active");
        // 注册用户默认归属默认租户（id=1，由 seeder 创建），并分配 ROLE_USER
        user.setTenantId(1L);
        userMapper.insert(user);
        // 分配默认角色 ROLE_USER
        Role userRole = roleMapper.selectByCode("ROLE_USER");
        if (userRole != null) {
            userMapper.insertUserRole(user.getId(), userRole.getId());
        }
        return user.getId();
    }

    @SuppressWarnings("unused")
    private Instant now() { return Instant.now(); }
}
