package com.aisys.storage.service;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.exception.CommonErrorCode;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.s3.service.S3StorageService;
import com.aisys.storage.constant.StorageConstants;
import com.aisys.storage.constant.StorageErrorCode;
import com.aisys.storage.dto.UploadTaskDtos;
import com.aisys.storage.entity.StoragePool;
import com.aisys.storage.entity.UploadTask;
import com.aisys.storage.mapper.UploadTaskMapper;
import com.aisys.storage.mapper.StoragePoolMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上传任务（大文件分片）：浏览器→存储后端(逐片)→后端写 S3 multipart part→complete 组装入池。
 * <p>浏览器全程只与存储后端通信，不直连存储池。任务状态机：UPLOADING→PROCESSING→COMPLETED/FAILED。
 */
@Service
public class UploadTaskService {

    private static final Logger log = LoggerFactory.getLogger(UploadTaskService.class);
    private static final long MIN_CHUNK = 5L * 1024 * 1024;   // 5 MiB（S3 分片最小）
    private static final int MAX_PARTS = 10000;               // S3 分片上限

    private final UploadTaskMapper taskMapper;
    private final S3StorageService s3;
    private final StoragePathUtil pathUtil;
    private final StoragePoolService poolService;
    private final StoragePoolMapper poolMapper;
    private final ObjectMapper objectMapper;

    public UploadTaskService(UploadTaskMapper taskMapper, S3StorageService s3, StoragePathUtil pathUtil,
                             StoragePoolService poolService, StoragePoolMapper poolMapper, ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.s3 = s3;
        this.pathUtil = pathUtil;
        this.poolService = poolService;
        this.poolMapper = poolMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UploadTaskDtos.InitiateResponse initiate(UploadTaskDtos.Initiate req) {
        if (req.size() == null || req.size() <= 0) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST, "size 非法");
        }
        long chunk = req.chunkSize() == null ? MIN_CHUNK : req.chunkSize();
        if (chunk < MIN_CHUNK) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST, "chunkSize 不能小于 5MB");
        }
        int total = (int) ((req.size() + chunk - 1) / chunk);
        if (total > MAX_PARTS) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST, "分片数超过 10000，请增大 chunkSize");
        }
        StoragePool pool = resolvePool(req.poolId());
        validateQuota(pool, req.size());   // 仅校验配额（不预占，避免 used_bytes 漂移）
        String key = pathUtil.resolveTenantKey(req.path());
        String uploadId = s3.createMultipart(s3Bucket(pool), key);

        UploadTask t = new UploadTask();
        t.setTenantId(UserContext.getTenantId());
        t.setPoolId(pool.getId());
        t.setRelativePath(req.path());
        t.setFileName(req.fileName());
        t.setSizeBytes(req.size());
        t.setChunkSize(chunk);
        t.setTotalChunks(total);
        t.setStatus("UPLOADING");
        t.setUploadId(uploadId);
        t.setBucket(s3Bucket(pool));
        t.setCreatedBy(UserContext.getUserId());
        taskMapper.insert(t);
        return new UploadTaskDtos.InitiateResponse(t.getId(), pool.getId(), req.path(), req.size(), chunk, total, "UPLOADING");
    }

    @Transactional
    public UploadTaskDtos.ChunkResponse uploadChunk(Long taskId, int partNumber, java.io.InputStream in, long contentLength) {
        UploadTask t = requireTask(taskId);
        if (!"UPLOADING".equals(t.getStatus())) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST, "任务状态非 UPLOADING，无法上传分片");
        }
        if (partNumber < 1 || partNumber > t.getTotalChunks()) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST, "分片序号越界");
        }
        // 后端流式直写 part 到存储池（浏览器→后端→池）
        String etag = s3.uploadPart(t.getBucket(), pathUtil.resolveTenantKey(t.getRelativePath()),
                t.getUploadId(), partNumber, in, contentLength);
        // 幂等收片：返回 DB 权威 received_chunks（避免用方法入口快照+1 在并发下虚低）
        Integer received = taskMapper.addPart(taskId, partNumber, etag);
        return new UploadTaskDtos.ChunkResponse(partNumber, etag,
                received == null ? (t.getReceivedChunks() == null ? 1 : t.getReceivedChunks() + 1) : received,
                t.getTotalChunks());
    }

    @Transactional
    public UploadTaskDtos.Response complete(Long taskId) {
        UploadTask t = requireTask(taskId);
        if ("COMPLETED".equals(t.getStatus())) return toResponse(t);
        if (!"UPLOADING".equals(t.getStatus())) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST, "任务状态非 UPLOADING，无法完成");
        }
        taskMapper.updateStatus(taskId, "PROCESSING", null);
        String key = pathUtil.resolveTenantKey(t.getRelativePath());
        try {
            List<Map<String, Object>> parts = readParts(taskMapper.selectById(taskId).getParts());
            // 幂等去重（partNumber 唯一）+ 完整性校验，避免重传导致 S3 complete 收到重复 part
            Map<Integer, CompletedPart> byPart = new LinkedHashMap<>();
            for (Map<String, Object> p : parts) {
                Object pn = p.get("partNumber");
                if (pn == null) continue;
                int n = ((Number) pn).intValue();
                byPart.put(n, CompletedPart.builder().partNumber(n).eTag(String.valueOf(p.get("etag"))).build());
            }
            int total = t.getTotalChunks() == null ? 0 : t.getTotalChunks();
            for (int n = 1; n <= total; n++) {
                if (!byPart.containsKey(n)) {
                    throw new BusinessException(CommonErrorCode.BAD_REQUEST, "缺失分片: " + n);
                }
            }
            List<CompletedPart> completed = byPart.values().stream()
                    .sorted(Comparator.comparingInt(CompletedPart::partNumber))
                    .toList();
            s3.completeMultipart(t.getBucket(), key, t.getUploadId(), completed);
            taskMapper.complete(taskId, "COMPLETED", key);
            // 已用配额累加（用量）；失败仅告警，不阻断（对象已组装入池，阻断会留下孤儿对象）
            try {
                poolMapper.addUsedBytes(t.getPoolId(), t.getSizeBytes() == null ? 0 : t.getSizeBytes());
            } catch (Exception e) {
                log.warn("[Upload] 配额累加失败，used_bytes 可能漂移，需对账 taskId={} poolId={} size={}",
                        taskId, t.getPoolId(), t.getSizeBytes(), e);
            }
        } catch (BusinessException e) {
            abortQuiet(t, key);
            taskMapper.updateStatus(taskId, "FAILED", truncate(e.getMessage()));
            throw e;
        } catch (Exception e) {
            log.error("complete 上传任务失败 taskId={}", taskId, e);
            abortQuiet(t, key);
            taskMapper.updateStatus(taskId, "FAILED", truncate(e.getMessage()));
            throw new BusinessException(StorageErrorCode.INTERNAL_ERROR, "组装失败: " + e.getMessage());
        }
        return toResponse(taskMapper.selectById(taskId));
    }

    /** 取消/中止上传任务：中止 S3 端 multipart（释放未完成分片占用的空间）并标记 CANCELLED。 */
    @Transactional
    public UploadTaskDtos.Response cancel(Long taskId) {
        UploadTask t = requireTask(taskId);
        String status = t.getStatus();
        if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
            return toResponse(t);
        }
        if (t.getUploadId() != null && t.getBucket() != null) {
            String key = pathUtil.resolveTenantKey(t.getRelativePath());
            s3.abortMultipart(t.getBucket(), key, t.getUploadId());
        }
        taskMapper.updateStatus(taskId, "CANCELLED", null);
        return toResponse(taskMapper.selectById(taskId));
    }

    private void abortQuiet(UploadTask t, String key) {
        if (t.getUploadId() != null && t.getBucket() != null) {
            s3.abortMultipart(t.getBucket(), key, t.getUploadId());
        }
    }

    @Transactional(readOnly = true)
    public PageResult<UploadTaskDtos.Response> list(String status, int page, int size) {
        // 平台超管看全部；租户用户只看本租户
        Long tenantId = UserContext.isPlatformAdmin() ? null : UserContext.getTenantId();
        long total = taskMapper.count(tenantId, status);
        int offset = Math.max(0, (page - 1) * size);
        List<UploadTaskDtos.Response> items = taskMapper.list(tenantId, status, offset, size).stream()
                .map(this::toResponse).toList();
        return PageResult.of(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public UploadTaskDtos.Response get(Long taskId) {
        return toResponse(requireTask(taskId));
    }

    // ---------- helpers ----------
    private UploadTask requireTask(Long id) {
        UploadTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(StorageErrorCode.BAD_REQUEST, "上传任务不存在");
        return t;
    }

    private StoragePool resolvePool(Long poolId) {
        StoragePool pool = poolId == null ? poolService.ensureDefaultPool() : poolService.getById(poolId);
        if (!StorageConstants.STATUS_ACTIVE.equalsIgnoreCase(pool.getStatus())) {
            throw new BusinessException(StorageErrorCode.POOL_INACTIVE);
        }
        return pool;
    }

    private String s3Bucket(StoragePool pool) {
        return (pool.getBucket() != null && !pool.getBucket().isBlank()) ? pool.getBucket() : s3.bucket();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readParts(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(json);
            // 新格式：partNumber 为键的 JSONB 对象（addPart 幂等写入）
            if (node.isObject()) {
                List<Map<String, Object>> out = new ArrayList<>();
                node.properties().forEach(e -> {
                    JsonNode v = e.getValue();
                    if (v.isObject()) {
                        out.add(objectMapper.convertValue(v, Map.class));
                    }
                });
                return out;
            }
            // 旧格式/兼容：数组
            if (node.isArray()) {
                return new ArrayList<>(objectMapper.convertValue(node, List.class));
            }
        } catch (Exception e) {
            // ignore
        }
        return new ArrayList<>();
    }

    /** 配额校验（仅校验，不预占 —— 预占无法在 complete 确认，会导致 used_bytes 漂移）。 */
    private void validateQuota(StoragePool pool, long incoming) {
        long quota = pool.getQuotaBytes() == null ? 0L : pool.getQuotaBytes();
        long used = pool.getUsedBytes() == null ? 0L : pool.getUsedBytes();
        if (quota > 0 && used + incoming > quota) {
            throw new BusinessException(StorageErrorCode.QUOTA_EXCEEDED,
                    String.format("配额超额：used=%d incoming=%d quota=%d", used, incoming, quota));
        }
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 500 ? s.substring(0, 500) : s);
    }

    private UploadTaskDtos.Response toResponse(UploadTask t) {
        return new UploadTaskDtos.Response(t.getId(), t.getPoolId(), t.getRelativePath(), t.getFileName(),
                t.getSizeBytes() == null ? 0 : t.getSizeBytes(),
                t.getChunkSize() == null ? 0 : t.getChunkSize(),
                t.getTotalChunks() == null ? 0 : t.getTotalChunks(),
                t.getReceivedChunks() == null ? 0 : t.getReceivedChunks(),
                t.getStatus(), t.getFinalKey(), t.getErrorMsg(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
