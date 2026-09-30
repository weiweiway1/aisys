package com.aisys.storage.service.impl;

import com.aisys.common.core.constant.CommonConstants;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.mq.outbox.EventPublisher;
import com.aisys.common.s3.service.S3StorageService;
import com.aisys.storage.constant.StorageConstants;
import com.aisys.storage.constant.StorageErrorCode;
import com.aisys.storage.dto.FileDtos;
import com.aisys.storage.entity.StoragePool;
import com.aisys.storage.mapper.StoragePoolMapper;
import com.aisys.storage.mq.StorageEventMessage;
import com.aisys.storage.service.FileService;
import com.aisys.storage.service.StoragePathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件操作服务实现（DDD 5.8.1 / 4.2.1）。
 * <p>核心安全：所有 prefix/path 经 {@link StoragePathUtil} 强制拼租户前缀并拒绝路径穿越。
 * <p>配额：upload-url / initiate(multipart) 时校验 quota_bytes，超额拒绝并发 storage.event。
 * <p>秒传：initiate 携带 fileHash，按 dedup/{tenantId}/{hash} 命中对象则返回 dedup 模式。
 */
@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    private static final int DEFAULT_PART_SIZE = 5 * 1024 * 1024; // 5 MiB，S3 分片最小要求
    private static final int MAX_BROWSE_LIMIT = 1000;

    private final StoragePoolMapper poolMapper;
    private final S3StorageService s3;
    private final S3Client s3Client;
    private final StoragePathUtil pathUtil;
    private final EventPublisher eventPublisher;

    public FileServiceImpl(StoragePoolMapper poolMapper, S3StorageService s3, S3Client s3Client,
                           StoragePathUtil pathUtil, EventPublisher eventPublisher) {
        this.poolMapper = poolMapper;
        this.s3 = s3;
        this.s3Client = s3Client;
        this.pathUtil = pathUtil;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public FileDtos.BrowseResult browse(Long poolId, String prefix, int limit) {
        StoragePool pool = requireActivePool(poolId);
        int max = Math.max(1, Math.min(limit <= 0 ? 100 : limit, MAX_BROWSE_LIMIT));
        String resolvedPrefix = pathUtil.resolveTenantKey(prefix);

        List<FileDtos.BrowseItem> items = new ArrayList<>();
        String sep = StorageConstants.TENANT_ROOT_SEPARATOR;
        // S3 列举前缀：保证恰好以单个分隔符结尾（resolvedPrefix 已含租户前缀，可能已带 /）
        String queryPrefix = resolvedPrefix.isEmpty()
                ? ""
                : (resolvedPrefix.endsWith(sep) ? resolvedPrefix : resolvedPrefix + sep);
        try {
            // 用 delimiter 让 S3 按"虚拟目录"分组：commonPrefixes 为目录，contents 为当前层级文件
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                    .bucket(bucket(pool))
                    .maxKeys(max)
                    .delimiter(sep);
            if (!queryPrefix.isEmpty()) {
                req.prefix(queryPrefix);
            }
            ListObjectsV2Response resp = s3Client.listObjectsV2(req.build());
            // 目录项：commonPrefixes 返回以分隔符结尾的"文件夹"前缀
            if (resp.hasCommonPrefixes()) {
                for (CommonPrefix cp : resp.commonPrefixes()) {
                    String dirKey = cp.prefix();
                    if (dirKey == null || dirKey.equals(queryPrefix)) continue;
                    items.add(new FileDtos.BrowseItem(pathUtil.displayName(dirKey), shortName(pathUtil.displayName(dirKey), sep),
                            0L, null, null, true));
                }
            }
            // 文件项：当前层级下的实际对象（排除目录占位对象）
            for (S3Object obj : resp.contents()) {
                if (obj.key().equals(queryPrefix)) continue;
                items.add(new FileDtos.BrowseItem(
                        pathUtil.displayName(obj.key()),
                        shortName(pathUtil.displayName(obj.key()), sep),
                        obj.size() == null ? 0L : obj.size(),
                        null,
                        obj.lastModified() == null ? null : obj.lastModified().atOffset(java.time.ZoneOffset.UTC),
                        false));
            }
        } catch (S3Exception e) {
            log.warn("[Storage] browse 失败 bucket={} prefix={}: {}", bucket(pool), queryPrefix, e.getMessage());
            throw new BusinessException(StorageErrorCode.INTERNAL_ERROR, "列举对象失败: " + e.getMessage());
        }
        return new FileDtos.BrowseResult(pool.getId(), resolvedPrefix, items);
    }

    /** 取相对路径的最后一段作为展示名（目录键以分隔符结尾时先去除）。 */
    private static String shortName(String rel, String sep) {
        if (rel == null || rel.isEmpty()) return "";
        String s = rel.endsWith(sep) ? rel.substring(0, rel.length() - sep.length()) : rel;
        int i = s.lastIndexOf(sep);
        return i >= 0 ? s.substring(i + 1) : s;
    }

    @Override
    @Transactional
    public FileDtos.UploadUrlResponse uploadUrl(Long poolId, FileDtos.UploadUrlRequest req) {
        StoragePool pool = requireActivePool(poolId);
        String key = pathUtil.resolveTenantKey(req.path());

        // 配额校验
        if (req.size() != null && req.size() > 0) {
            checkQuota(pool, req.size());
        }

        String url = s3.presignUpload(bucket(pool), key);
        return new FileDtos.UploadUrlResponse(pool.getId(), key, url, "PUT", presignSeconds());
    }

    @Override
    public FileDtos.DownloadUrlResponse downloadUrl(Long poolId, String path) {
        StoragePool pool = requireActivePool(poolId);
        String key = pathUtil.resolveLenient(path);
        if (!s3.objectExists(bucket(pool), key)) {
            throw new BusinessException(StorageErrorCode.FILE_NOT_FOUND);
        }
        String url = s3.presignDownload(bucket(pool), key);
        return new FileDtos.DownloadUrlResponse(pool.getId(), key, url, presignSeconds());
    }

    @Override
    @Transactional
    public void delete(Long poolId, FileDtos.DeleteRequest req) {
        StoragePool pool = requireActivePool(poolId);
        if (req.keys() == null || req.keys().isEmpty()) {
            throw new BusinessException(StorageErrorCode.BAD_REQUEST, "keys 不能为空");
        }
        // 对每个 key 容错解析：已拼租户前缀的（来自 initiate/uploadUrl 返回）原样用，否则拼前缀；避免双重前缀删不到对象
        List<String> safeKeys = new ArrayList<>(req.keys().size());
        for (String k : req.keys()) {
            safeKeys.add(pathUtil.resolveLenient(k));
        }
        // 已用配额回收（尽力统计大小）
        long freed = 0;
        for (String k : safeKeys) {
            try { freed += s3.objectSize(bucket(pool), k); } catch (Exception ignored) {}
        }
        s3.deleteObjects(bucket(pool), safeKeys);
        if (freed > 0) poolMapper.addUsedBytes(pool.getId(), -freed);

        eventPublisher.publish(
                StorageEventMessage.fileDeleted(pool.getId(), List.copyOf(safeKeys)),
                CommonConstants.EXCHANGE_STORAGE_EVENT,
                "File",
                String.valueOf(pool.getId()));   // aggregate_id 受 varchar(64) 限制；完整 keys 已在 payload 内
    }

    // ---------- 内部上传协议（DDD 4.2.1） ----------

    @Override
    @Transactional
    public FileDtos.InitiateResponse initiate(FileDtos.InitiateRequest req) {
        // 默认池（内部协议不显式传 poolId，使用 default）
        StoragePool pool = requireDefaultPool();
        String key = pathUtil.resolveTenantKey(req.path());

        // 1) 秒传：按 hash 命中
        if (req.fileHash() != null && !req.fileHash().isBlank()) {
            String dedupKey = pathUtil.dedupKey(req.fileHash());
            if (s3.objectExists(bucket(pool), dedupKey)) {
                log.info("[Storage] 秒传命中: key={} dedup={}", key, dedupKey);
                return new FileDtos.InitiateResponse(
                        "dedup", pool.getId(), dedupKey, null, List.of(), null, dedupKey, presignSeconds());
            }
        }

        // 2) 配额校验
        if (req.size() != null && req.size() > 0) {
            checkQuota(pool, req.size());
        }

        // 3) 直传 vs 分片
        boolean useMultipart = req.multipart()
                || (req.size() != null && req.size() > DEFAULT_PART_SIZE);
        if (!useMultipart) {
            String url = s3.presignUpload(bucket(pool), key);
            return new FileDtos.InitiateResponse(
                    "direct", pool.getId(), key, null, List.of(), url, null, presignSeconds());
        }

        int partSize = (req.partSize() != null && req.partSize() > 0) ? req.partSize() : DEFAULT_PART_SIZE;
        long size = req.size() == null ? 0L : req.size();
        int partCount = Math.max(1, (int) Math.ceil(size / (double) partSize));
        String uploadId = s3.createMultipart(bucket(pool), key);
        List<FileDtos.PresignedPart> parts = new ArrayList<>(partCount);
        for (int n = 1; n <= partCount; n++) {
            String pUrl = s3.presignUploadPart(bucket(pool), key, uploadId, n);
            parts.add(new FileDtos.PresignedPart(n, pUrl));
        }
        return new FileDtos.InitiateResponse(
                "multipart", pool.getId(), key, uploadId, parts, null, null, presignSeconds());
    }

    @Override
    public FileDtos.CompleteResponse complete(FileDtos.CompleteRequest req) {
        StoragePool pool = requireDefaultPool();
        // 越权校验：complete 的 key 必须落在当前租户命名空间（防跨租户 complete）。
        // initiate 已返回拼好租户前缀的 key，客户端应原样回传；此处不再重新加前缀。
        String key = req.key();
        if (!pathUtil.belongsToCurrentTenant(key)) {
            throw new BusinessException(StorageErrorCode.INVALID_PATH, "complete 的 key 不属于当前租户");
        }
        try {
            List<CompletedPart> parts = new ArrayList<>();
            if (req.parts() != null) {
                for (FileDtos.CompletePart p : req.parts()) {
                    parts.add(CompletedPart.builder()
                            .partNumber(p.partNumber())
                            .eTag(p.etag())
                            .build());
                }
            }
            s3.completeMultipart(bucket(pool), key, req.uploadId(), parts);
            return new FileDtos.CompleteResponse(key, true);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Storage] complete multipart 失败 key={} uploadId={}: {}", key, req.uploadId(), e.getMessage());
            throw new BusinessException(StorageErrorCode.UPLOAD_COMPLETE_FAILED, e.getMessage());
        }
    }

    // ---------- helpers ----------

    private void checkQuota(StoragePool pool, long incoming) {
        long quota = pool.getQuotaBytes() == null ? 0L : pool.getQuotaBytes();
        long used = pool.getUsedBytes() == null ? 0L : pool.getUsedBytes();
        if (quota > 0 && used + incoming > quota) {
            // 发配额超额事件（同事务写入 outbox，保证一致）
            eventPublisher.publish(
                    StorageEventMessage.quotaExceeded(pool.getId(), pool.getName(), incoming, quota, used),
                    CommonConstants.EXCHANGE_STORAGE_EVENT,
                    "StoragePool",
                    String.valueOf(pool.getId()));
            throw new BusinessException(StorageErrorCode.QUOTA_EXCEEDED,
                    String.format("配额超额：used=%d incoming=%d quota=%d", used, incoming, quota));
        }
        // 仅校验，不预占：预签名/内部上传无法在 complete 时确认回填，预占会导致 used_bytes 永久虚高（漂移）。
        // 实际用量由 storeUpload/UploadTask.complete 的 addUsedBytes 累加，并由 usage() 实时列举对账。
    }

    private StoragePool requireActivePool(Long poolId) {
        if (poolId == null) {
            return requireDefaultPool();
        }
        StoragePool pool = poolMapper.selectById(poolId);
        if (pool == null) throw new BusinessException(StorageErrorCode.POOL_NOT_FOUND);
        if (!StorageConstants.STATUS_ACTIVE.equalsIgnoreCase(pool.getStatus())) {
            throw new BusinessException(StorageErrorCode.POOL_INACTIVE);
        }
        return pool;
    }

    private StoragePool requireDefaultPool() {
        StoragePool pool = poolMapper.selectByName(StorageConstants.DEFAULT_POOL_NAME);
        if (pool == null) {
            // 不应发生（启动时已 ensure），兜底
            throw new BusinessException(StorageErrorCode.POOL_NOT_FOUND, "默认存储池未初始化");
        }
        if (!StorageConstants.STATUS_ACTIVE.equalsIgnoreCase(pool.getStatus())) {
            throw new BusinessException(StorageErrorCode.POOL_INACTIVE);
        }
        return pool;
    }

    @Override
    public FileDtos.DownloadUrlResponse directUpload(Long poolId, String filename, byte[] bytes, String contentType) {
        StoragePool pool = requireActivePool(poolId);
        String safeName = filename == null ? "file" : filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String key = pathUtil.resolveTenantKey("uploads/" + System.currentTimeMillis() + "_" + safeName);
        s3.putBytes(bucket(pool), key, bytes, contentType);
        String url = s3.presignDownload(bucket(pool), key);
        return new FileDtos.DownloadUrlResponse(pool.getId(), key, url, presignSeconds());
    }

    @Override
    public FileDtos.UploadResult storeUpload(Long poolId, String path, String filename,
                                             java.io.InputStream in, long size, String contentType) {
        StoragePool pool = requireActivePool(poolId);
        String relPath;
        if (path != null && !path.isBlank()) {
            relPath = path;
        } else {
            String safeName = filename == null ? "file" : filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            relPath = "uploads/" + System.currentTimeMillis() + "_" + safeName;
        }
        String key = pathUtil.resolveTenantKey(relPath);
        s3.putStream(bucket(pool), key, in, size, contentType);
        try { poolMapper.addUsedBytes(pool.getId(), size); } catch (Exception ignored) {}
        return new FileDtos.UploadResult(pool.getId(), relPath, size);
    }

    @Override
    public java.io.InputStream downloadStream(Long poolId, String path) {
        StoragePool pool = requireActivePool(poolId);
        if (path == null || path.isBlank()) {
            throw new BusinessException(StorageErrorCode.BAD_REQUEST, "path 不能为空");
        }
        String key = pathUtil.resolveLenient(path);
        return s3.getStream(bucket(pool), key);
    }

    private String bucket(StoragePool pool) {
        if (pool.getBucket() != null && !pool.getBucket().isBlank()) {
            return pool.getBucket();
        }
        return s3.bucket();
    }

    private int presignSeconds() {
        return (int) Duration.ofMinutes(Math.max(1, s3.presignDuration().toMinutes())).getSeconds();
    }
}
