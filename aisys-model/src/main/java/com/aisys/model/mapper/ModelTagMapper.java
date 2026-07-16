package com.aisys.model.mapper;

import com.aisys.model.entity.ModelTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型标签 Mapper（树形，RLS 表）。
 */
@Mapper
public interface ModelTagMapper {

    ModelTag selectById(@Param("id") Long id);

    /** 列出租户内全部标签（RLS 隐式过滤 tenant_id），Service 侧组装树 */
    List<ModelTag> selectAll();

    List<ModelTag> selectByParentId(@Param("parentId") Long parentId);

    int insert(ModelTag tag);

    int update(ModelTag tag);

    int deleteById(@Param("id") Long id);
}
