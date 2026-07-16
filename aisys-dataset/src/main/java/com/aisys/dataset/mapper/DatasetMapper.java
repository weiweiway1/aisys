package com.aisys.dataset.mapper;

import com.aisys.dataset.entity.Dataset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 数据集 Mapper（DDD 5.4）。租户作用域由 RLS 自动过滤。 */
@Mapper
public interface DatasetMapper {

    Dataset selectById(@Param("id") Long id);

    long count(@Param("keyword") String keyword,
               @Param("type") String type,
               @Param("taskType") String taskType,
               @Param("projectId") Long projectId,
               @Param("status") String status);

    List<Dataset> page(@Param("keyword") String keyword,
                       @Param("type") String type,
                       @Param("taskType") String taskType,
                       @Param("projectId") Long projectId,
                       @Param("status") String status,
                       @Param("offset") int offset,
                       @Param("size") int size);

    int insert(Dataset dataset);

    int update(Dataset dataset);

    int softDelete(@Param("id") Long id);
}
