package com.aisys.model.mapper;

import com.aisys.model.entity.Model;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型 Mapper（RLS 表，租户作用域访问必须在 @Transactional 内）。
 */
@Mapper
public interface ModelMapper {

    Model selectById(@Param("id") Long id);

    /** 校验租户内 name 唯一（tenantId 由 RLS 隐式约束） */
    Model selectByName(@Param("name") String name);

    long count(@Param("keyword") String keyword,
               @Param("status") String status,
               @Param("projectId") Long projectId);

    List<Model> page(@Param("keyword") String keyword,
                     @Param("status") String status,
                     @Param("projectId") Long projectId,
                     @Param("offset") int offset,
                     @Param("size") int size);

    int insert(Model model);

    int update(Model model);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int deleteById(@Param("id") Long id);

    /** 删除模型下所有版本对应的存储对象前缀时，需先查出 storage_path（跨事务用 admin 池读取） */
    List<String> selectVersionStoragePaths(@Param("modelId") Long modelId);
}
