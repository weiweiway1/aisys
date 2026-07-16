package com.aisys.model.service.impl;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.mq.outbox.EventPublisher;
import com.aisys.common.s3.service.S3StorageService;
import com.aisys.model.constant.ModelConstants;
import com.aisys.model.constant.ModelErrorCode;
import com.aisys.model.dto.ModelDtos.ModelCreateRequest;
import com.aisys.model.dto.ModelDtos.ModelDetailResponse;
import com.aisys.model.dto.ModelDtos.ModelListResponse;
import com.aisys.model.dto.ModelDtos.ModelQueryRequest;
import com.aisys.model.dto.ModelDtos.ModelUpdateRequest;
import com.aisys.model.entity.Model;
import com.aisys.model.entity.ModelVersion;
import com.aisys.model.mapper.ModelMapper;
import com.aisys.model.mapper.ModelVersionMapper;
import com.aisys.model.mq.ModelLifecycleMessage;
import com.aisys.model.service.ModelService;
import com.aisys.common.core.constant.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 模型领域服务实现（DDD 5.3）。
 * <p>租户作用域的 DB 访问全部在 @Transactional 内（RLS SET LOCAL 不跨语句）。
 * 存储对象直连 common-s3（S3StorageService）读写，key 约定 {tenantId}/{relPath}；删除放事务外避免长事务。
 */
@Service
public class ModelServiceImpl implements ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelServiceImpl.class);

    private final ModelMapper modelMapper;
    private final ModelVersionMapper versionMapper;
    private final EventPublisher eventPublisher;
    private final S3StorageService s3;

    public ModelServiceImpl(ModelMapper modelMapper,
                            ModelVersionMapper versionMapper,
                            EventPublisher eventPublisher,
                            S3StorageService s3) {
        this.modelMapper = modelMapper;
        this.versionMapper = versionMapper;
        this.eventPublisher = eventPublisher;
        this.s3 = s3;
    }

    // ---------- 查询 ----------

    @Override
    @Transactional(readOnly = true)
    public PageResult<ModelListResponse> listModels(ModelQueryRequest req) {
        int page = req.page() == null || req.page() < 1 ? 1 : req.page();
        int size = req.size() == null || req.size() < 1 ? 20 : req.size();
        int offset = (page - 1) * size;

        long total = modelMapper.count(req.keyword(), req.status(), req.projectId());
        if (total == 0) {
            return PageResult.empty(page, size);
        }
        List<Model> rows = modelMapper.page(req.keyword(), req.status(), req.projectId(), offset, size);
        List<ModelListResponse> items = rows.stream().map(m -> new ModelListResponse(
                m.getId(), m.getTenantId(), m.getProjectId(), m.getName(),
                m.getType(), m.getFramework(), m.getDescription(),
                m.getStatus(), m.getVisibility(), m.getCreatedBy(),
                m.getCreatedAt(), m.getUpdatedAt())).toList();
        return PageResult.of(items, total, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public ModelDetailResponse getModel(Long id) {
        return toDetail(getModelOrThrow(id));
    }

    // ---------- 创建 ----------

    @Override
    @Transactional
    public ModelDetailResponse createModel(ModelCreateRequest request) {
        Long tenantId = requireTenant();

        // 校验租户内 name 唯一
        Model exists = modelMapper.selectByName(request.name());
        if (exists != null) {
            throw new BusinessException(ModelErrorCode.MODEL_NAME_DUPLICATE);
        }

        Model m = new Model();
        m.setTenantId(tenantId);
        m.setProjectId(request.projectId());
        m.setName(request.name());
        m.setType(request.type());
        m.setFramework(request.framework());
        m.setDescription(request.description());
        m.setStatus(ModelConstants.STATUS_DRAFT);
        m.setVisibility(request.visibility() == null ? "private" : request.visibility());
        m.setCreatedBy(UserContext.getUserId());
        modelMapper.insert(m);
        log.info("[Model] 创建模型 id={} tenant={} name={}", m.getId(), tenantId, m.getName());
        return toDetail(m);
    }

    // ---------- 更新 ----------

    @Override
    @Transactional
    public ModelDetailResponse updateModel(Long id, ModelUpdateRequest request) {
        Model m = getModelOrThrow(id);

        // 改名时重新校验唯一性
        if (request.name() != null && !request.name().equals(m.getName())) {
            Model other = modelMapper.selectByName(request.name());
            if (other != null && !other.getId().equals(id)) {
                throw new BusinessException(ModelErrorCode.MODEL_NAME_DUPLICATE);
            }
            m.setName(request.name());
        }
        if (request.projectId() != null) m.setProjectId(request.projectId());
        if (request.type() != null) m.setType(request.type());
        if (request.framework() != null) m.setFramework(request.framework());
        if (request.description() != null) m.setDescription(request.description());
        if (request.visibility() != null) m.setVisibility(request.visibility());
        modelMapper.update(m);
        log.info("[Model] 更新模型 id={}", id);
        return toDetail(m);
    }

    // ---------- 删除 ----------

    @Override
    @Transactional
    public void deleteModel(Long id) {
        Model m = getModelOrThrow(id);
        // 1) 先收集要删除的存储路径（RLS 内读）
        List<String> paths = modelMapper.selectVersionStoragePaths(id);
        // 2) 删 DB（model + 级联 model_version）
        modelMapper.deleteById(id);
        // 3) outbox：发 MODEL_DELETED 事件（事务内）
        publishLifecycle(ModelConstants.MSG_MODEL_DELETED, m, null, null);
        log.info("[Model] 删除模型 id={} 待清理对象数={}", id, paths.size());
        // 4) 事务提交后异步删存储对象（best-effort，失败仅记日志）
        deleteStorageObjectsBestEffort(m.getTenantId(), paths);
    }

    // ---------- 状态机：draft → published → deprecated → archived ----------

    @Override
    @Transactional
    public void publishModel(Long id) {
        Model m = getModelOrThrow(id);
        ensureTransition(m.getStatus(), ModelConstants.STATUS_PUBLISHED);
        modelMapper.updateStatus(id, ModelConstants.STATUS_PUBLISHED);
        // 取最新 ready 版本一并发事件（若存在）
        ModelVersion ready = pickReadyVersion(id);
        publishLifecycle(ModelConstants.MSG_MODEL_PUBLISHED, m, ready, ModelConstants.STATUS_PUBLISHED);
        log.info("[Model] 发布模型 id={}", id);
    }

    @Override
    @Transactional
    public void deprecateModel(Long id) {
        Model m = getModelOrThrow(id);
        ensureTransition(m.getStatus(), ModelConstants.STATUS_DEPRECATED);
        modelMapper.updateStatus(id, ModelConstants.STATUS_DEPRECATED);
        publishLifecycle(ModelConstants.MSG_MODEL_DEPRECATED, m, null, ModelConstants.STATUS_DEPRECATED);
        log.info("[Model] 废弃模型 id={}", id);
    }

    @Override
    @Transactional
    public void archiveModel(Long id) {
        Model m = getModelOrThrow(id);
        ensureTransition(m.getStatus(), ModelConstants.STATUS_ARCHIVED);
        modelMapper.updateStatus(id, ModelConstants.STATUS_ARCHIVED);
        publishLifecycle(ModelConstants.MSG_MODEL_ARCHIVED, m, null, ModelConstants.STATUS_ARCHIVED);
        log.info("[Model] 归档模型 id={}", id);
    }

    // ---------- 辅助 ----------

    private Model getModelOrThrow(Long id) {
        Model m = modelMapper.selectById(id);
        if (m == null) {
            throw new BusinessException(ModelErrorCode.MODEL_NOT_FOUND);
        }
        return m;
    }

    private Long requireTenant() {
        Long t = UserContext.getTenantId();
        if (t == null) {
            throw new BusinessException(ModelErrorCode.TENANT_REQUIRED);
        }
        return t;
    }

    /**
     * 校验状态转换合法性：draft→published→deprecated→archived 单向链。
     */
    private void ensureTransition(String from, String to) {
        boolean ok = switch (to) {
            case ModelConstants.STATUS_PUBLISHED  -> ModelConstants.STATUS_DRAFT.equals(from);
            case ModelConstants.STATUS_DEPRECATED -> ModelConstants.STATUS_PUBLISHED.equals(from);
            case ModelConstants.STATUS_ARCHIVED   -> ModelConstants.STATUS_PUBLISHED.equals(from)
                    || ModelConstants.STATUS_DEPRECATED.equals(from);
            default -> false;
        };
        if (!ok) {
            throw new BusinessException(ModelErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private ModelVersion pickReadyVersion(Long modelId) {
        List<ModelVersion> vs = versionMapper.selectByModelId(modelId);
        return vs.stream()
                .filter(v -> ModelConstants.VERSION_STATUS_READY.equals(v.getStatus()))
                .reduce((first, second) -> second) // 取最后一个（按 created_at DESC）
                .orElse(null);
    }

    private void publishLifecycle(String messageType, Model m, ModelVersion v, String status) {
        ModelLifecycleMessage msg = new ModelLifecycleMessage(messageType);
        msg.setModelId(m.getId());
        msg.setName(m.getName());
        msg.setStatus(status);
        if (v != null) {
            msg.setVersionId(v.getId());
            msg.setVersion(v.getVersion());
        }
        eventPublisher.publish(msg,
                CommonConstants.EXCHANGE_NOTIFICATION_EVENT,
                ModelConstants.AGGREGATE_MODEL,
                String.valueOf(m.getId()));
    }

    /** 删除版本对象（best-effort，失败仅记日志）。paths 为租户相对路径，按 {tenantId}/{relPath} 拼全 key 删除。 */
    private void deleteStorageObjectsBestEffort(Long tenantId, List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        List<String> keys = paths.stream().map(p -> fullKey(tenantId, p)).toList();
        try {
            s3.deleteObjects(s3.bucket(), keys);
        } catch (Exception e) {
            log.warn("[Model] 删除版本对象失败（best-effort）keys={}: {}", keys, e.getMessage());
        }
    }

    /** 拼接实际 S3 对象 key：{tenantId}/{relPath}，与 ModelVersionServiceImpl.fullKey 约定一致。 */
    private String fullKey(Long tenantId, String relPath) {
        if (relPath == null) return null;
        if (tenantId == null) return relPath;
        return tenantId + "/" + relPath;
    }

    private ModelDetailResponse toDetail(Model m) {
        Object tags = null;
        if (m.getTags() != null) {
            try {
                tags = MAPPER_HELPER.parse(m.getTags());
            } catch (Exception ignored) {
                tags = m.getTags();
            }
        }
        return new ModelDetailResponse(
                m.getId(), m.getTenantId(), m.getProjectId(), m.getName(),
                m.getType(), m.getFramework(), m.getDescription(),
                tags, m.getStatus(), m.getVisibility(),
                m.getCreatedBy(), m.getCreatedAt(), m.getUpdatedAt());
    }

    /** 轻量 JSON 解析（避免在循环里反复构造 ObjectMapper）。tags 列原样以 text 返回，这里仅尝试转结构。 */
    private static final class MAPPER_HELPER {
        private static final tools.jackson.databind.ObjectMapper M = new tools.jackson.databind.ObjectMapper();

        static Object parse(String json) throws tools.jackson.core.JacksonException {
            return M.readValue(json, Object.class);
        }
    }
}
