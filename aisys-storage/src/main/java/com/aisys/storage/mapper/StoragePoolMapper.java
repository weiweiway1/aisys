package com.aisys.storage.mapper;

import com.aisys.storage.entity.StoragePool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 存储池 Mapper（平台共享表，无 RLS）。 */
@Mapper
public interface StoragePoolMapper {

    StoragePool selectById(@Param("id") Long id);

    StoragePool selectByName(@Param("name") String name);

    List<StoragePool> page(@Param("keyword") String keyword,
                           @Param("offset") int offset,
                           @Param("size") int size);

    long count(@Param("keyword") String keyword);

    int insert(StoragePool pool);

    int update(StoragePool pool);

    int deleteById(@Param("id") Long id);

    /** 增加已用量（配额预占/释放，size 可为负）。 */
    int addUsedBytes(@Param("id") Long id, @Param("delta") long delta);
}
