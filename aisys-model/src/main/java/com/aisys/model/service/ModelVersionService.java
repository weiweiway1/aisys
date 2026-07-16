package com.aisys.model.service;

import com.aisys.model.dto.VersionDtos.ModelVersionCreateRequest;
import com.aisys.model.dto.VersionDtos.ModelVersionResponse;
import com.aisys.model.dto.VersionDtos.UploadCompleteRequest;
import com.aisys.model.dto.VersionDtos.UploadCompleteResponse;
import com.aisys.model.dto.VersionDtos.UploadInitRequest;
import com.aisys.model.dto.VersionDtos.UploadInitResponse;

import java.util.List;

/**
 * 模型版本 / 分片上传领域服务（DDD 5.3）。
 */
public interface ModelVersionService {

    List<ModelVersionResponse> listVersions(Long modelId);

    /** 按版本 ID 查询单个版本（跨服务解析镜像 tar 路径用）。 */
    ModelVersionResponse getVersionById(Long versionId);

    ModelVersionResponse createVersion(Long modelId, ModelVersionCreateRequest request);

    UploadInitResponse initiateUpload(Long modelId, Long versionId, UploadInitRequest request);

    UploadCompleteResponse completeUpload(Long modelId, Long versionId, UploadCompleteRequest request);
}
