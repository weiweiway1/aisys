package com.aisys.auth.mapper;

import com.aisys.auth.entity.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RoleMapper {

    List<Role> selectAll();

    Role selectByCode(@Param("code") String code);

    Role selectById(@Param("id") Long id);

    int insert(Role role);

    int update(Role role);

    int deleteById(@Param("id") Long id);

    void insertRolePermissions(@Param("roleId") Long roleId, @Param("permissionIds") List<Long> permissionIds);

    void deleteRolePermissions(@Param("roleId") Long roleId);

    List<Long> selectPermissionIdsByRoleId(@Param("roleId") Long roleId);
}
