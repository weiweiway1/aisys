package com.aisys.auth.service;

import com.aisys.auth.constant.AuthErrorCode;
import com.aisys.auth.dto.RoleDtos;
import com.aisys.auth.dto.RoleResponse;
import com.aisys.auth.entity.Permission;
import com.aisys.auth.entity.Role;
import com.aisys.auth.mapper.PermissionMapper;
import com.aisys.auth.mapper.RoleMapper;
import com.aisys.common.core.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 角色/权限查询与管理（前端角色分配用）。 */
@Service
public class RoleService {

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;

    public RoleService(RoleMapper roleMapper, PermissionMapper permissionMapper) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return roleMapper.selectAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<Permission> listPermissions() {
        return permissionMapper.selectAll();
    }

    @Transactional
    public RoleResponse create(RoleDtos.Create req) {
        if (roleMapper.selectByCode(req.code()) != null) {
            throw new BusinessException(AuthErrorCode.BAD_REQUEST, "角色编码已存在: " + req.code());
        }
        Role role = new Role();
        role.setCode(req.code());
        role.setName(req.name());
        role.setDescription(req.description());
        role.setIsSystem(false);
        roleMapper.insert(role);
        return toResponse(roleMapper.selectById(role.getId()));
    }

    @Transactional
    public RoleResponse update(Long id, RoleDtos.Update req) {
        Role role = requireRole(id);
        role.setName(req.name());
        role.setDescription(req.description());
        roleMapper.update(role);
        return toResponse(roleMapper.selectById(id));
    }

    @Transactional
    public void delete(Long id) {
        Role role = requireRole(id);
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BusinessException(AuthErrorCode.NO_PERMISSION, "系统内置角色不可删除");
        }
        roleMapper.deleteRolePermissions(id);
        roleMapper.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<Long> getPermissionIds(Long roleId) {
        requireRole(roleId);
        return roleMapper.selectPermissionIdsByRoleId(roleId);
    }

    @Transactional
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        requireRole(roleId);
        roleMapper.deleteRolePermissions(roleId);
        if (permissionIds == null) return;
        // 过滤 null 并去重，防止前端误传 undefined→null 触发 permission_id NOT NULL 违约
        List<Long> valid = permissionIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!valid.isEmpty()) {
            roleMapper.insertRolePermissions(roleId, valid);
        }
    }

    private Role requireRole(Long id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(AuthErrorCode.BAD_REQUEST, "角色不存在: id=" + id);
        }
        return role;
    }

    private RoleResponse toResponse(Role r) {
        return new RoleResponse(r.getId(), r.getCode(), r.getName(), r.getDescription(),
                Boolean.TRUE.equals(r.getIsSystem()), roleMapper.selectPermissionIdsByRoleId(r.getId()));
    }
}
