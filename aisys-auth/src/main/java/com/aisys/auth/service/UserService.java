package com.aisys.auth.service;

import com.aisys.auth.constant.AuthErrorCode;
import com.aisys.auth.dto.UserDtos;
import com.aisys.auth.entity.Role;
import com.aisys.auth.entity.User;
import com.aisys.auth.mapper.RoleMapper;
import com.aisys.auth.mapper.UserMapper;
import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 用户管理（管理员）：列表/详情/创建/更新/删除/分配角色（DDD 5.2.1）。 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public UserService(UserMapper userMapper, RoleMapper roleMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<UserDtos.Response> list(String keyword, int page, int size) {
        // 平台超管看全部；租户管理员只看本租户
        Long tenantId = UserContext.isPlatformAdmin() ? null : UserContext.getTenantId();
        long total = userMapper.count(tenantId, keyword);
        List<User> users = userMapper.page(tenantId, keyword, Math.max(0, (page - 1) * size), size);
        List<UserDtos.Response> items = users.stream().map(this::toResponse).toList();
        return PageResult.of(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public UserDtos.Response get(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(AuthErrorCode.USER_NOT_FOUND);
        ensureTenantScope(user);
        return toResponse(user);
    }

    @Transactional
    public UserDtos.Response create(UserDtos.Create req) {
        if (userMapper.selectByUsername(req.username()) != null) {
            throw new BusinessException(AuthErrorCode.USERNAME_EXISTS);
        }
        User u = new User();
        u.setUsername(req.username());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setEmail(req.email());
        u.setNickname(req.nickname());
        u.setPhone(req.phone());
        u.setStatus("active");
        u.setTenantId(UserContext.isPlatformAdmin() ? null : UserContext.getTenantId());
        userMapper.insert(u);
        assignRolesByCodes(u.getId(), req.roleCodes());
        return toResponse(userMapper.selectById(u.getId()));
    }

    @Transactional
    public UserDtos.Response update(Long id, UserDtos.Update req) {
        User u = userMapper.selectById(id);
        if (u == null) throw new BusinessException(AuthErrorCode.USER_NOT_FOUND);
        ensureTenantScope(u);
        if (req.email() != null) u.setEmail(req.email());
        if (req.nickname() != null) u.setNickname(req.nickname());
        if (req.phone() != null) u.setPhone(req.phone());
        if (req.status() != null) u.setStatus(req.status());
        userMapper.update(u);
        return toResponse(userMapper.selectById(id));
    }

    @Transactional
    public void delete(Long id) {
        User u = userMapper.selectById(id);
        if (u != null) {
            ensureTenantScope(u);
            userMapper.deleteById(id);
        }
    }

    @Transactional
    public void assignRoles(Long id, List<Long> roleIds) {
        User u = userMapper.selectById(id);
        if (u == null) throw new BusinessException(AuthErrorCode.USER_NOT_FOUND);
        ensureTenantScope(u);
        if (roleIds == null || roleIds.isEmpty()) {
            userMapper.deleteUserRoles(id);
            return;
        }
        // 安全校验：非平台超管不得授予系统级/平台级角色（如 ROLE_PLATFORM_ADMIN），
        // 否则租户管理员可给自己或同租户用户授予 BYPASSRLS 权限 → 跨租户提权。
        if (!UserContext.isPlatformAdmin()) {
            for (Long rid : roleIds) {
                Role r = roleMapper.selectById(rid);
                if (r == null || Boolean.TRUE.equals(r.getIsSystem())) {
                    throw new BusinessException(AuthErrorCode.NO_PERMISSION);
                }
            }
        }
        userMapper.deleteUserRoles(id);
        userMapper.replaceRoles(id, roleIds);
    }

    private void assignRolesByCodes(Long userId, List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) return;
        boolean platformAdmin = UserContext.isPlatformAdmin();
        // 同 assignRoles：非平台超管创建用户时也不得授予系统级角色
        List<Long> roleIds = new java.util.ArrayList<>();
        for (String code : roleCodes) {
            Role r = roleMapper.selectByCode(code);
            if (r == null) continue;
            if (!platformAdmin && Boolean.TRUE.equals(r.getIsSystem())) {
                throw new BusinessException(AuthErrorCode.NO_PERMISSION);
            }
            roleIds.add(r.getId());
        }
        userMapper.deleteUserRoles(userId);
        if (!roleIds.isEmpty()) userMapper.replaceRoles(userId, roleIds);
    }

    private UserDtos.Response toResponse(User u) {
        List<String> roles = u == null ? List.of() : userMapper.selectRoleCodesByUserId(u.getId());
        return new UserDtos.Response(u.getId(), u.getTenantId(), u.getUsername(), u.getEmail(),
                u.getPhone(), u.getNickname(), u.getStatus(), roles, u.getLastLoginAt(), u.getCreatedAt());
    }

    /** 租户管理员只能操作本租户用户；平台超管不受限。 */
    private void ensureTenantScope(User u) {
        if (!UserContext.isPlatformAdmin() && u.getTenantId() != null
                && !u.getTenantId().equals(UserContext.getTenantId())) {
            throw new BusinessException(AuthErrorCode.NO_PERMISSION);
        }
    }
}
