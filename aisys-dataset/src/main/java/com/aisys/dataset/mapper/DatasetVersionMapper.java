package com.aisys.dataset.mapper;

import com.aisys.dataset.entity.DatasetVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 数据集版本 Mapper（DDD 5.4）。 */
@Mapper
public interface DatasetVersionMapper {

    DatasetVersion selectById(@Param("id") Long id);

    DatasetVersion selectByDatasetAndVersion(@Param("datasetId") Long datasetId,
                                             @Param("version") String version);

    List<DatasetVersion> listByDatasetId(@Param("datasetId") Long datasetId);

    int insert(DatasetVersion version);

    int updateStatus(@Param("id") Long id, @Param("status") String status,
                     @Param("fileSize") Long fileSize, @Param("checksum") String checksum,
                     @Param("rowCount") Long rowCount, @Param("columnInfo") String columnInfo);

    int deleteById(@Param("id") Long id);

    /** 删除某数据集的全部版本（数据集软删时级联清理）。 */
    int deleteByDatasetId(@Param("datasetId") Long datasetId);
}
