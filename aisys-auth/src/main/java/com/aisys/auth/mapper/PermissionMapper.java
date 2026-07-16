package com.aisys.auth.mapper;

import com.aisys.auth.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PermissionMapper {

    List<Permission> selectAll();

    Permission selectByCode(@Param("code") String code);

    int insert(Permission permission);
}
