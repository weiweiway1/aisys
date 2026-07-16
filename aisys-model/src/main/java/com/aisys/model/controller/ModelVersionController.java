package com.aisys.model.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.model.dto.VersionDtos.ModelVersionCreateRequest;
import com.aisys.model.dto.VersionDtos.ModelVersionResponse;
import com.aisys.model.dto.VersionDtos.UploadCompleteRequest;
import com.aisys.model.dto.VersionDtos.UploadCompleteResponse;
import com.aisys.model.dto.VersionDtos.UploadInitRequest;
import com.aisys.model.dto.VersionDtos.UploadInitResponse;
import com.aisys.model.service.ModelVersionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型版本 / 分片上传接口（DDD 5.3.1）。
 * <p>路径前缀 /api/v1/models/{modelId}/versions。
 * <ul>
 *   <li>POST /            创建版本（status=creating，等待上传）。</li>
 *   <li>POST /{versionId}/upload/initiate  S3 createMultipart + 分片预签名；命中秒传则 dedup=true。</li>
 *   <li>POST /{versionId}/upload/complete  S3 completeMultipart，更新 version 为 ready，发 MODEL_PUBLISHED 通知。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/models/{modelId}/versions")
public class ModelVersionController {

    private final ModelVersionService versionService;

    public ModelVersionController(ModelVersionService versionService) {
        this.versionService = versionService;
    }

    @GetMapping
    public ApiResponse<List<ModelVersionResponse>> list(@PathVariable Long modelId) {
        return ApiResponse.success(versionService.listVersions(modelId));
    }

    @PostMapping
    @AuditLog(action = "CREATE", resource = "MODEL_VERSION", description = "创建模型版本",
            resourceId = "#result.data.id")
    public ApiResponse<ModelVersionResponse> create(@PathVariable Long modelId,
                                                    @Valid @RequestBody ModelVersionCreateRequest request) {
        return ApiResponse.success(versionService.createVersion(modelId, request));
    }

    @PostMapping("/{versionId}/upload/initiate")
    @AuditLog(action = "UPLOAD_INIT", resource = "MODEL_VERSION", description = "初始化分片上传",
            resourceId = "#versionId")
    public ApiResponse<UploadInitResponse> initiateUpload(@PathVariable Long modelId,
                                                          @PathVariable Long versionId,
                                                          @Valid @RequestBody UploadInitRequest request) {
        return ApiResponse.success(versionService.initiateUpload(modelId, versionId, request));
    }

    @PostMapping("/{versionId}/upload/complete")
    @AuditLog(action = "UPLOAD_COMPLETE", resource = "MODEL_VERSION", description = "完成分片上传",
            resourceId = "#versionId")
    public ApiResponse<UploadCompleteResponse> completeUpload(@PathVariable Long modelId,
                                                              @PathVariable Long versionId,
                                                              @Valid @RequestBody UploadCompleteRequest request) {
        return ApiResponse.success(versionService.completeUpload(modelId, versionId, request));
    }
}
