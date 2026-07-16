package com.aisys.evaluation.mapper;

import com.aisys.evaluation.entity.Benchmark;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Benchmark Mapper。租户作用域访问须在 @Transactional 内（含 readOnly）以保证 SET LOCAL 生效。
 */
@Mapper
public interface BenchmarkMapper {

    void insert(Benchmark benchmark);

    int update(Benchmark benchmark);

    int updateStatus(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("status") String status);

    Benchmark selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);

    List<Benchmark> selectPage(@Param("tenantId") Long tenantId,
                               @Param("keyword") String keyword,
                               @Param("category") String category,
                               @Param("status") String status,
                               @Param("offset") int offset,
                               @Param("size") int size);

    long count(@Param("tenantId") Long tenantId,
               @Param("keyword") String keyword,
               @Param("category") String category,
               @Param("status") String status);

    int deleteByIdAndTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
