package com.aisys.model.mapper;

import com.aisys.model.entity.ModelVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型版本 Mapper（RLS 表）。
 */
@Mapper
public interface ModelVersionMapper {

    ModelVersion selectById(@Param("id") Long id);

    ModelVersion selectByModelAndVersion(@Param("modelId") Long modelId,
                                         @Param("version") String version);

    /** 秒传：按 checksum 查同模型同租户内已 ready 的版本（限定 model_id，避免复用其它模型的存储路径） */
    ModelVersion selectReadyByChecksum(@Param("checksum") String checksum, @Param("modelId") Long modelId);

    List<ModelVersion> selectByModelId(@Param("modelId") Long modelId);

    int insert(ModelVersion version);

    int update(ModelVersion version);

    int updateUploadResult(@Param("id") Long id,
                           @Param("storagePath") String storagePath,
                           @Param("fileSize") Long fileSize,
                           @Param("checksum") String checksum,
                           @Param("status") String status);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int deleteByModelId(@Param("modelId") Long modelId);

    int deleteById(@Param("id") Long id);
}
