package com.aisys.model.service.impl;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.mq.outbox.EventPublisher;
import com.aisys.common.s3.service.S3StorageService;
import com.aisys.model.constant.ModelConstants;
import com.aisys.model.constant.ModelErrorCode;
import com.aisys.model.dto.VersionDtos.ChunkUrl;
import com.aisys.model.dto.VersionDtos.CompletedPart;
import com.aisys.model.dto.VersionDtos.ModelVersionCreateRequest;
import com.aisys.model.dto.VersionDtos.ModelVersionResponse;
import com.aisys.model.dto.VersionDtos.UploadCompleteRequest;
import com.aisys.model.dto.VersionDtos.UploadCompleteResponse;
import com.aisys.model.dto.VersionDtos.UploadInitRequest;
import com.aisys.model.dto.VersionDtos.UploadInitResponse;
import com.aisys.model.entity.Model;
import com.aisys.model.entity.ModelVersion;
import com.aisys.model.mapper.ModelMapper;
import com.aisys.model.mapper.ModelVersionMapper;
import com.aisys.model.mq.ModelLifecycleMessage;
import com.aisys.model.service.ModelVersionService;
import com.aisys.common.core.constant.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型版本 / 分片上传领域服务实现（DDD 5.3）。
 * <p>分片上传直接走 common-s3（S3StorageService.createMultipart + presignUploadPart + completeMultipart）。
 * 秒传：按 fileHash（checksum）查 model_version 已存在同 checksum → 返回 dedup=true。
 */
@Service
public class ModelVersionServiceImpl implements ModelVersionService {

    private static final Logger log = LoggerFactory.getLogger(ModelVersionServiceImpl.class);

    private final ModelMapper modelMapper;
    private final ModelVersionMapper versionMapper;
    private final S3StorageService s3;
    private final EventPublisher eventPublisher;
    private final long defaultChunkSize;

    public ModelVersionServiceImpl(ModelMapper modelMapper,
                                   ModelVersionMapper versionMapper,
                                   S3StorageService s3,
                                   EventPublisher eventPublisher,
                                   @Value("${aisys.model.default-chunk-size:16777216}") long defaultChunkSize) {
        this.modelMapper = modelMapper;
        this.versionMapper = versionMapper;
        this.s3 = s3;
        this.eventPublisher = eventPublisher;
        this.defaultChunkSize = defaultChunkSize;
    }

    // ---------- 查询 ----------

    @Override
    @Transactional(readOnly = true)
    public List<ModelVersionResponse> listVersions(Long modelId) {
        ensureModelExists(modelId);
        List<ModelVersion> rows = versionMapper.selectByModelId(modelId);
        return rows.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ModelVersionResponse getVersionById(Long versionId) {
        ModelVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new BusinessException(ModelErrorCode.MODEL_VERSION_NOT_FOUND);
        }
        return toResponse(v);
    }

    // ---------- 创建版本（status=creating，等待上传） ----------

    @Override
    @Transactional
    public ModelVersionResponse createVersion(Long modelId, ModelVersionCreateRequest request) {
        Model model = ensureModelExists(modelId);
        // 校验同模型版本号唯一
        ModelVersion exists = versionMapper.selectByModelAndVersion(modelId, request.version());
        if (exists != null) {
            throw new BusinessException(ModelErrorCode.VERSION_DUPLICATE);
        }
        ModelVersion v = new ModelVersion();
        v.setModelId(modelId);
        v.setTenantId(model.getTenantId());
        v.setVersion(request.version());
        v.setConfig(configToJson(request.config()));
        v.setStatus(ModelConstants.VERSION_STATUS_CREATING);
        v.setCreatedBy(UserContext.getUserId());
        versionMapper.insert(v);
        log.info("[ModelVersion] 创建版本 modelId={} versionId={} version={}", modelId, v.getId(), v.getVersion());
        return toResponse(v);
    }

    // ---------- 分片上传：initiate ----------

    @Override
    @Transactional
    public UploadInitResponse initiateUpload(Long modelId, Long versionId, UploadInitRequest request) {
        Model model = ensureModelExists(modelId);
        ModelVersion v = getVersionOrThrow(versionId);
        if (!modelId.equals(v.getModelId())) {
            throw new BusinessException(ModelErrorCode.BAD_REQUEST);
        }

        // 秒传：按 checksum（fileHash）查同租户已 ready 的版本
        if (request.fileHash() != null && !request.fileHash().isBlank()) {
            ModelVersion dup = versionMapper.selectReadyByChecksum(request.fileHash(), modelId);
            if (dup != null) {
                // 直接把本版本置 ready 并复用存储路径，客户端无需再上传
                versionMapper.updateUploadResult(versionId,
                        dup.getStoragePath(), dup.getFileSize(), request.fileHash(),
                        ModelConstants.VERSION_STATUS_READY);
                log.info("[ModelVersion] 秒传命中 versionId={} 复用 path={}", versionId, dup.getStoragePath());
                return new UploadInitResponse(null, dup.getStoragePath(), 0, 0, List.of(), true);
            }
        }

        // 计算 storage key 与分片
        long chunkSize = request.chunkSize() != null && request.chunkSize() > 0
                ? request.chunkSize() : defaultChunkSize;
        long fileSize = request.fileSize() != null ? request.fileSize() : 0L;
        int chunkCount = fileSize <= 0 ? 1 : (int) Math.ceil((double) fileSize / chunkSize);
        // storagePath 为租户相对路径（DB storage_path 约定，与 dataset 一致），
        // 实际 S3 对象 key 由 Resource/调度侧用 {tenantId}/ 拼全 —— 这里上传也写到同一全 key，
        // 避免「上传 key」与「预签名下载 key」错位导致 Agent 拉不到镜像 tar。
        String storagePath = storageKey(modelId, v.getVersion());
        String objectKey = fullKey(model.getTenantId(), storagePath);

        // S3 createMultipart
        String uploadId;
        try {
            uploadId = s3.createMultipart(s3.bucket(), objectKey);
        } catch (Exception e) {
            log.warn("[ModelVersion] createMultipart 失败 key={}: {}", objectKey, e.getMessage());
            throw new BusinessException(ModelErrorCode.UPLOAD_INIT_FAILED);
        }

        // 为每个分片生成预签名 URL（partNumber 从 1 开始）
        List<ChunkUrl> urls = new ArrayList<>(chunkCount);
        for (int i = 1; i <= chunkCount; i++) {
            String url = s3.presignUploadPart(s3.bucket(), objectKey, uploadId, i);
            urls.add(new ChunkUrl(i, url));
        }

        log.info("[ModelVersion] initiateUpload versionId={} uploadId={} key={} chunks={}", versionId, uploadId, objectKey, chunkCount);
        return new UploadInitResponse(uploadId, storagePath, chunkCount, chunkSize, urls, false);
    }

    // ---------- 分片上传：complete ----------

    @Override
    @Transactional
    public UploadCompleteResponse completeUpload(Long modelId, Long versionId, UploadCompleteRequest request) {
        Model model = ensureModelExists(modelId);
        ModelVersion v = getVersionOrThrow(versionId);
        if (!modelId.equals(v.getModelId())) {
            throw new BusinessException(ModelErrorCode.BAD_REQUEST);
        }

        // 秒传场景：version 已是 ready 且无 uploadId，直接返回（不发重复事件）
        if (ModelConstants.VERSION_STATUS_READY.equals(v.getStatus())
                && (request.uploadId() == null || request.uploadId().isBlank())) {
            return new UploadCompleteResponse(versionId, v.getStoragePath(),
                    v.getFileSize(), v.getChecksum(), v.getStatus());
        }

        if (request.uploadId() == null || request.uploadId().isBlank()
                || request.parts() == null || request.parts().isEmpty()) {
            throw new BusinessException(ModelErrorCode.INVALID_PARTS);
        }
        // storagePath 为租户相对路径（DB storage_path 约定），objectKey 为实际 S3 对象 key（{tenantId}/{relPath}）。
        String storagePath = storageKey(modelId, v.getVersion());
        String objectKey = fullKey(model.getTenantId(), storagePath);

        // 组装 AWS SDK CompletedPart 并 completeMultipart。
        // 注意：VersionDtos.CompletedPart（本服务 DTO）与 software.amazon.awssdk...CompletedPart（SDK）同名，
        // 这里用全限定名区分，避免 import 冲突。
        List<software.amazon.awssdk.services.s3.model.CompletedPart> parts =
                new ArrayList<>(request.parts().size());
        for (CompletedPart p : request.parts()) {
            if (p.partNumber() < 1 || p.etag() == null || p.etag().isBlank()) {
                throw new BusinessException(ModelErrorCode.INVALID_PARTS);
            }
            parts.add(software.amazon.awssdk.services.s3.model.CompletedPart.builder()
                    .partNumber(p.partNumber()).eTag(p.etag()).build());
        }
        try {
            s3.completeMultipart(s3.bucket(), objectKey, request.uploadId(), parts);
        } catch (Exception e) {
            log.warn("[ModelVersion] completeMultipart 失败 key={}: {}", objectKey, e.getMessage());
            versionMapper.updateStatus(versionId, ModelConstants.VERSION_STATUS_FAILED);
            s3.abortMultipart(s3.bucket(), objectKey, request.uploadId());
            throw new BusinessException(ModelErrorCode.UPLOAD_COMPLETE_FAILED);
        }

        // 更新 version（storage_path 存租户相对路径；file_size/checksum/status=ready）
        long fileSize = request.fileSize() != null ? request.fileSize() : 0L;
        versionMapper.updateUploadResult(versionId, storagePath, fileSize, request.checksum(),
                ModelConstants.VERSION_STATUS_READY);

        // 发 MODEL_PUBLISHED 通知（upload/complete 规格：发 notification.event(MODEL_PUBLISHED)）
        publishVersionEvent(ModelConstants.MSG_MODEL_PUBLISHED, model, v, request.checksum());

        log.info("[ModelVersion] completeUpload versionId={} key={} size={}", versionId, objectKey, fileSize);
        return new UploadCompleteResponse(versionId, storagePath, fileSize, request.checksum(),
                ModelConstants.VERSION_STATUS_READY);
    }

    // ---------- 辅助 ----------

    private Model ensureModelExists(Long modelId) {
        Model m = modelMapper.selectById(modelId);
        if (m == null) {
            throw new BusinessException(ModelErrorCode.MODEL_NOT_FOUND);
        }
        return m;
    }

    private ModelVersion getVersionOrThrow(Long versionId) {
        ModelVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new BusinessException(ModelErrorCode.MODEL_VERSION_NOT_FOUND);
        }
        return v;
    }

    private String storageKey(Long modelId, String version) {
        return String.format(ModelConstants.STORAGE_KEY_FORMAT, modelId, version);
    }

    /** 拼接实际 S3 对象 key：{tenantId}/{relPath}，与 dataset/storage 模块约定一致；tenantId 为空时退化为 relPath。 */
    private String fullKey(Long tenantId, String relPath) {
        if (relPath == null) return null;
        if (tenantId == null) return relPath;
        return tenantId + "/" + relPath;
    }

    /** config（Object，如 {imageName,...}）序列化为 JSON 字符串存储。 */
    private String configToJson(Object config) {
        if (config == null) return null;
        if (config instanceof String s) return s;
        try {
            return MAPPER_HELPER.M.writeValueAsString(config);
        } catch (tools.jackson.core.JacksonException e) {
            log.warn("[ModelVersion] config 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private void publishVersionEvent(String messageType, Model m, ModelVersion v, String checksum) {
        ModelLifecycleMessage msg = new ModelLifecycleMessage(messageType);
        msg.setModelId(m.getId());
        msg.setName(m.getName());
        msg.setVersionId(v.getId());
        msg.setVersion(v.getVersion());
        msg.setStatus(ModelConstants.VERSION_STATUS_READY);
        msg.setExtra(checksum == null ? null : java.util.Map.of("checksum", checksum));
        eventPublisher.publish(msg,
                CommonConstants.EXCHANGE_NOTIFICATION_EVENT,
                ModelConstants.AGGREGATE_MODEL,
                String.valueOf(m.getId()));
    }

    private ModelVersionResponse toResponse(ModelVersion v) {
        Object config = null;
        if (v.getConfig() != null) {
            try {
                config = MAPPER_HELPER.parse(v.getConfig());
            } catch (Exception ignored) {
                config = v.getConfig();
            }
        }
        return new ModelVersionResponse(
                v.getId(), v.getModelId(), v.getVersion(), v.getStoragePath(),
                v.getFileSize(), v.getChecksum(), v.getStatus(),
                config, v.getCreatedBy(), v.getCreatedAt(), v.getUpdatedAt());
    }

    private static final class MAPPER_HELPER {
        private static final tools.jackson.databind.ObjectMapper M = new tools.jackson.databind.ObjectMapper();

        static Object parse(String json) throws tools.jackson.core.JacksonException {
            return M.readValue(json, Object.class);
        }
    }
}
