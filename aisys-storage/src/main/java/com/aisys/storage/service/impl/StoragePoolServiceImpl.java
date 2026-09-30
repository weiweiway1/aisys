package com.aisys.storage.service.impl;

import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.mq.outbox.EventPublisher;
import com.aisys.common.s3.config.S3Properties;
import com.aisys.common.s3.service.S3StorageService;
import com.aisys.storage.config.StorageProperties;
import com.aisys.storage.constant.StorageConstants;
import com.aisys.storage.constant.StorageErrorCode;
import com.aisys.storage.dto.StoragePoolDtos;
import com.aisys.storage.entity.StoragePool;
import com.aisys.storage.mapper.StoragePoolMapper;
import com.aisys.storage.mq.StorageEventMessage;
import com.aisys.storage.service.StoragePoolService;
import com.aisys.common.core.constant.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 存储池服务实现（平台共享表 storage_pool，无 RLS；DDD 5.8.1）。 */
@Service
public class StoragePoolServiceImpl implements StoragePoolService {

    private static final Logger log = LoggerFactory.getLogger(StoragePoolServiceImpl.class);

    private final StoragePoolMapper poolMapper;
    private final S3StorageService s3;
    private final S3Properties s3Properties;
    private final StorageProperties storageProperties;
    private final EventPublisher eventPublisher;

    public StoragePoolServiceImpl(StoragePoolMapper poolMapper, S3StorageService s3,
                                  S3Properties s3Properties, StorageProperties storageProperties,
                                  EventPublisher eventPublisher) {
        this.poolMapper = poolMapper;
        this.s3 = s3;
        this.s3Properties = s3Properties;
        this.storageProperties = storageProperties;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StoragePoolDtos.Response> list(String keyword, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(size, 200));
        long total = poolMapper.count(keyword);
        int offset = (p - 1) * s;
        List<StoragePool> rows = poolMapper.page(keyword, offset, s);
        List<StoragePoolDtos.Response> items = rows.stream().map(StoragePoolServiceImpl::toResponse).toList();
        return PageResult.of(items, total, p, s);
    }

    @Override
    @Transactional(readOnly = true)
    public StoragePoolDtos.Response get(Long id) {
        return toResponse(requirePool(id));
    }

    @Override
    @Transactional
    public StoragePoolDtos.Response create(StoragePoolDtos.Create req) {
        if (poolMapper.selectByName(req.name()) != null) {
            throw new BusinessException(StorageErrorCode.POOL_NAME_EXISTS);
        }
        StoragePool pool = new StoragePool();
        pool.setName(req.name());
        pool.setType(req.type() == null || req.type().isBlank() ? StorageConstants.DEFAULT_POOL_TYPE : req.type());
        pool.setEndpoint(req.endpoint());
        pool.setBucket(req.bucket());
        pool.setAccessKey(req.accessKey());
        pool.setSecretKey(req.secretKey());
        pool.setQuotaBytes(req.quotaBytes());
        pool.setUsedBytes(0L);
        pool.setStatus(req.status() == null ? StorageConstants.STATUS_ACTIVE : req.status());
        poolMapper.insert(pool);
        ensureBucket(pool);
        return toResponse(pool);
    }

    @Override
    @Transactional
    public StoragePoolDtos.Response update(Long id, StoragePoolDtos.Create req) {
        StoragePool pool = requirePool(id);
        if (req.name() != null && !req.name().equals(pool.getName())) {
            StoragePool existing = poolMapper.selectByName(req.name());
            if (existing != null && !existing.getId().equals(id)) {
                throw new BusinessException(StorageErrorCode.POOL_NAME_EXISTS);
            }
            pool.setName(req.name());
        }
        if (req.type() != null) pool.setType(req.type());
        if (req.endpoint() != null) pool.setEndpoint(req.endpoint());
        if (req.bucket() != null) pool.setBucket(req.bucket());
        if (req.accessKey() != null) pool.setAccessKey(req.accessKey());
        if (req.secretKey() != null) pool.setSecretKey(req.secretKey());
        if (req.quotaBytes() != null) pool.setQuotaBytes(req.quotaBytes());
        if (req.status() != null) pool.setStatus(req.status());
        poolMapper.update(pool);
        ensureBucket(pool);

        eventPublisher.publish(
                StorageEventMessage.poolUpdated(pool.getId(), pool.getName()),
                CommonConstants.EXCHANGE_STORAGE_EVENT,
                "StoragePool",
                String.valueOf(pool.getId()));
        return toResponse(pool);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        StoragePool pool = requirePool(id);
        if (StorageConstants.DEFAULT_POOL_NAME.equalsIgnoreCase(pool.getName())) {
            throw new BusinessException(StorageErrorCode.BAD_REQUEST, "默认存储池不可删除");
        }
        poolMapper.deleteById(id);
    }

    @Override
    public StoragePoolDtos.Usage usage(Long id) {
        // 不加 @Transactional：本方法会对整个 bucket 做分页 ListObjectsV2（可能较慢），
        // 持有 DB 连接/事务会占用连接池；storage_pool 为平台共享表（无 RLS），单次 selectById 无需事务包裹。
        StoragePool pool = requirePool(id);
        long quota = pool.getQuotaBytes() == null ? 0L : pool.getQuotaBytes();
        String bucket = (pool.getBucket() != null && !pool.getBucket().isBlank()) ? pool.getBucket() : s3.bucket();
        long used;
        try {
            // 实时列举存储池对象求和（不再依赖会漂移的 used_bytes 列）
            used = s3.statBucket(bucket).totalBytes();
        } catch (Exception e) {
            used = pool.getUsedBytes() == null ? 0L : pool.getUsedBytes();
        }
        long free = Math.max(0, quota - used);
        double pct = quota <= 0 ? 0.0 : Math.min(100.0, (used * 100.0) / quota);
        return new StoragePoolDtos.Usage(pool.getId(), pool.getName(), quota, used, free, pct);
    }

    @Override
    @Transactional(readOnly = true)
    public StoragePool getByName(String name) {
        return poolMapper.selectByName(name);
    }

    @Override
    @Transactional(readOnly = true)
    public StoragePool getById(Long id) {
        return requirePool(id);
    }

    @Override
    @Transactional
    public StoragePool ensureDefaultPool() {
        String name = storageProperties.getDefaultPoolName();
        StoragePool pool = poolMapper.selectByName(name);
        if (pool != null) {
            return pool;
        }
        log.info("[Storage] 默认存储池不存在，自动创建: name={} quota={}", name, storageProperties.getDefaultPoolQuotaBytes());
        pool = new StoragePool();
        pool.setName(name);
        pool.setType(StorageConstants.DEFAULT_POOL_TYPE);
        pool.setEndpoint(s3Properties.getEndpoint());
        pool.setBucket(s3Properties.getBucket());
        pool.setAccessKey(s3Properties.getAccessKey());
        pool.setSecretKey(s3Properties.getSecretKey());
        pool.setQuotaBytes(storageProperties.getDefaultPoolQuotaBytes());
        pool.setUsedBytes(0L);
        pool.setStatus(StorageConstants.STATUS_ACTIVE);
        poolMapper.insert(pool);
        ensureBucket(pool);
        return pool;
    }

    private StoragePool requirePool(Long id) {
        if (id == null) throw new BusinessException(StorageErrorCode.BAD_REQUEST, "poolId 不能为空");
        StoragePool pool = poolMapper.selectById(id);
        if (pool == null) throw new BusinessException(StorageErrorCode.POOL_NOT_FOUND);
        return pool;
    }

    private void ensureBucket(StoragePool pool) {
        if (pool.getBucket() == null || pool.getBucket().isBlank()) return;
        try {
            s3.ensureBucket(pool.getBucket());
        } catch (Exception e) {
            log.warn("[Storage] ensureBucket({}) 失败（不阻断）: {}", pool.getBucket(), e.getMessage());
        }
    }

    private static StoragePoolDtos.Response toResponse(StoragePool pool) {
        return new StoragePoolDtos.Response(
                pool.getId(), pool.getName(), pool.getType(), pool.getEndpoint(), pool.getBucket(),
                pool.getAccessKey(), pool.getQuotaBytes(), pool.getUsedBytes(), pool.getStatus(),
                pool.getCreatedAt(), pool.getUpdatedAt());
    }
}
