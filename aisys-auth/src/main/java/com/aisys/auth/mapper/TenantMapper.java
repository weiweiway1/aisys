package com.aisys.auth.mapper;

import com.aisys.auth.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TenantMapper {

    Tenant selectById(@Param("id") Long id);

    Tenant selectByCode(@Param("code") String code);

    long count(@Param("keyword") String keyword);

    List<Tenant> page(@Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    int insert(Tenant tenant);

    int update(Tenant tenant);

    int deleteById(@Param("id") Long id);
}
