package com.aisys.storage.mapper;

import com.aisys.storage.entity.UploadTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 上传任务 Mapper（平台共享表 upload_task）。 */
@Mapper
public interface UploadTaskMapper {

    int insert(UploadTask task);

    UploadTask selectById(@Param("id") Long id);

    List<UploadTask> list(@Param("tenantId") Long tenantId,
                          @Param("status") String status,
                          @Param("offset") int offset,
                          @Param("size") int size);

    long count(@Param("tenantId") Long tenantId, @Param("status") String status);

    /** 收到一片：幂等追加 part（以 partNumber 为键，重传覆盖）并维护 received_chunks；返回更新后的 received_chunks。 */
    Integer addPart(@Param("id") Long id, @Param("partNumber") int partNumber, @Param("etag") String etag);

    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("errorMsg") String errorMsg);

    int complete(@Param("id") Long id, @Param("status") String status, @Param("finalKey") String finalKey);
}
