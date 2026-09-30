package com.aisys.auth.mapper;

import com.aisys.auth.entity.Role;
import com.aisys.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {

    User selectByUsername(@Param("username") String username);

    User selectById(@Param("id") Long id);

    long count(@Param("tenantId") Long tenantId, @Param("keyword") String keyword);

    List<User> page(@Param("tenantId") Long tenantId,
                    @Param("keyword") String keyword,
                    @Param("offset") int offset,
                    @Param("size") int size);

    int insert(User user);

    int update(User user);

    int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    int updateLastLogin(@Param("id") Long id);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int deleteById(@Param("id") Long id);

    List<Role> selectRolesByUserId(@Param("userId") Long userId);

    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    List<String> selectPermissionCodesByUserId(@Param("userId") Long userId);

    void insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    void deleteUserRoles(@Param("userId") Long userId);

    void replaceRoles(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);
}
