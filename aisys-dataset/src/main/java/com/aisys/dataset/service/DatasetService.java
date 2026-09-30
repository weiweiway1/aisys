package com.aisys.dataset.service;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.s3.service.S3StorageService;
import com.aisys.dataset.constant.DatasetErrorCode;
import com.aisys.dataset.dto.DatasetDtos;
import com.aisys.dataset.entity.Dataset;
import com.aisys.dataset.entity.DatasetVersion;
import com.aisys.dataset.mapper.DatasetMapper;
import com.aisys.dataset.mapper.DatasetVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 数据集元数据管理（DDD 5.4.1）。
 * <p>租户作用域 DB 访问全部在 @Transactional 内（含 readOnly），保证 RLS SET LOCAL 跨语句生效。
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    /** 列表允许的状态过滤值（绝不暴露 'deleted' 回收站）。 */
    private static final Set<String> ALLOWED_LIST_STATUS = Set.of("active", "archived", "draft", "ready", "deprecated");

    private final DatasetMapper datasetMapper;
    private final DatasetVersionMapper versionMapper;
    private final S3StorageService s3;
    private final ObjectMapper objectMapper;

    public DatasetService(DatasetMapper datasetMapper, DatasetVersionMapper versionMapper,
                          S3StorageService s3, ObjectMapper objectMapper) {
        this.datasetMapper = datasetMapper;
        this.versionMapper = versionMapper;
        this.s3 = s3;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<DatasetDtos.Response> list(String keyword, String type, Long projectId,
                                                 String taskType, String status, int page, int size) {
        // 仅允许白名单状态过滤；'deleted' 或非法值一律视为不过滤（回收站永不暴露）
        String safeStatus = (status != null && ALLOWED_LIST_STATUS.contains(status)) ? status : null;
        long total = datasetMapper.count(keyword, type, taskType, projectId, safeStatus);
        int offset = Math.max(0, (page - 1) * size);
        List<Dataset> datasets = datasetMapper.page(keyword, type, taskType, projectId, safeStatus, offset, size);
        List<DatasetDtos.Response> items = datasets.stream().map(this::toResponse).toList();
        return PageResult.of(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public DatasetDtos.Response get(Long id) {
        return toResponse(ensureActive(id));
    }

    @Transactional
    public DatasetDtos.Response create(DatasetDtos.Create req) {
        Dataset d = new Dataset();
        d.setProjectId(req.projectId());
        d.setName(req.name());
        d.setType(req.type());
        d.setFormat(req.format());
        d.setTaskType(req.taskType());
        d.setStoragePoolId(req.storagePoolId());
        d.setSampleCount(0L);
        d.setTags(tagsToJson(req.tags()));
        d.setDescription(req.description());
        d.setLicense(req.license());
        d.setTenantId(UserContext.getTenantId());
        d.setStatus("active");
        d.setCreatedBy(UserContext.getUserId());
        datasetMapper.insert(d);
        return toResponse(datasetMapper.selectById(d.getId()));
    }

    @Transactional
    public DatasetDtos.Response update(Long id, DatasetDtos.Update req) {
        Dataset d = ensureActive(id);
        if (req.name() != null) d.setName(req.name());
        if (req.type() != null) d.setType(req.type());
        if (req.format() != null) d.setFormat(req.format());
        if (req.taskType() != null) d.setTaskType(req.taskType());
        if (req.storagePoolId() != null) d.setStoragePoolId(req.storagePoolId());
        if (req.tags() != null) d.setTags(tagsToJson(req.tags()));
        if (req.description() != null) d.setDescription(req.description());
        if (req.license() != null) d.setLicense(req.license());
        // 状态变更仅允许白名单（禁止经 update 复活已软删记录或塞入 deleted）
        if (req.status() != null && ALLOWED_LIST_STATUS.contains(req.status())) d.setStatus(req.status());
        if (req.projectId() != null) d.setProjectId(req.projectId());
        datasetMapper.update(d);
        return toResponse(datasetMapper.selectById(id));
    }

    @Transactional
    public void delete(Long id) {
        ensureActive(id);
        // 级联清理版本：先收集 storagePath（软删前），尽力删 S3 对象，再删版本行，最后软删数据集。
        List<DatasetVersion> versions = versionMapper.listByDatasetId(id);
        datasetMapper.softDelete(id);
        for (DatasetVersion v : versions) {
            try {
                String key = v.getTenantId() == null ? v.getStoragePath() : v.getTenantId() + "/" + v.getStoragePath();
                s3.deleteObject(s3.bucket(), key);
            } catch (Exception e) {
                log.warn("删除版本对象失败 dataset={} version={} path={}：{}", id, v.getId(), v.getStoragePath(), e.getMessage());
            }
        }
        versionMapper.deleteByDatasetId(id);
    }

    /** 取活跃数据集；不存在或已软删均抛 NOT_FOUND。 */
    private Dataset ensureActive(Long id) {
        Dataset d = datasetMapper.selectById(id);
        if (d == null || "deleted".equals(d.getStatus())) {
            throw new BusinessException(DatasetErrorCode.DATASET_NOT_FOUND);
        }
        return d;
    }

    private DatasetDtos.Response toResponse(Dataset d) {
        return new DatasetDtos.Response(
                d.getId(), d.getTenantId(), d.getProjectId(), d.getName(), d.getType(),
                d.getFormat(), d.getTaskType(), d.getStoragePoolId(), d.getSampleCount(),
                parseTags(d.getTags()), d.getDescription(), d.getLicense(), d.getStatus(),
                d.getCreatedBy(), d.getCreatedAt(), d.getUpdatedAt()
        );
    }

    private String tagsToJson(List<String> tags) {
        if (tags == null) return null;
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            @SuppressWarnings("unchecked")
            List<String> list = objectMapper.readValue(json, List.class);
            return list == null ? Collections.emptyList() : new ArrayList<>(list);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
