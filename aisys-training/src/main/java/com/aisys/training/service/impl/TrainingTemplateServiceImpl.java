package com.aisys.training.service.impl;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import com.aisys.training.constant.TrainingErrorCode;
import com.aisys.training.dto.ResourceSpec;
import com.aisys.training.dto.TrainingTemplateDtos;
import com.aisys.training.entity.TrainingTask;
import com.aisys.training.entity.TrainingTemplate;
import com.aisys.training.mapper.TrainingTaskMapper;
import com.aisys.training.mapper.TrainingTemplateMapper;
import com.aisys.training.service.TrainingTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 训练模板领域服务实现（DDD 5.5）。 */
@Service
public class TrainingTemplateServiceImpl implements TrainingTemplateService {

    private static final Logger log = LoggerFactory.getLogger(TrainingTemplateServiceImpl.class);

    private final TrainingTemplateMapper templateMapper;
    private final TrainingTaskMapper taskMapper;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public TrainingTemplateServiceImpl(TrainingTemplateMapper templateMapper,
                                       TrainingTaskMapper taskMapper,
                                       tools.jackson.databind.ObjectMapper objectMapper) {
        this.templateMapper = templateMapper;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TrainingTemplateDtos.Response> list(String keyword, int page, int size) {
        Long tenantId = UserContext.isPlatformAdmin() ? null : UserContext.getTenantId();
        long total = templateMapper.count(tenantId, keyword);
        int offset = Math.max(0, (page - 1) * size);
        List<TrainingTemplate> rows = templateMapper.page(tenantId, keyword, offset, size);
        List<TrainingTemplateDtos.Response> items = rows.stream().map(this::toResponse).toList();
        return PageResult.of(items, total, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public TrainingTemplateDtos.Response get(Long id) {
        return toResponse(requireOwned(id));
    }

    @Override
    @Transactional
    public TrainingTemplateDtos.Response create(TrainingTemplateDtos.Create req) {
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException(TrainingErrorCode.TEMPLATE_INSTANTIATE_FAILED,
                    "缺少租户上下文，无法创建训练模板");
        }
        TrainingTemplate t = new TrainingTemplate();
        t.setTenantId(tenantId);
        t.setName(req.name());
        t.setDescription(req.description());
        t.setImage(req.image());
        t.setCommand(req.command());
        t.setDefaultResourceSpec(toJson(req.defaultResourceSpec()));
        t.setDefaultHyperparameters(toJson(req.defaultHyperparameters()));
        t.setCreatedBy(UserContext.getUserId());
        templateMapper.insert(t);
        log.info("创建训练模板 id={} name={}", t.getId(), t.getName());
        return toResponse(templateMapper.selectById(t.getId()));
    }

    @Override
    @Transactional
    public TrainingTemplateDtos.Response update(Long id, TrainingTemplateDtos.Update req) {
        TrainingTemplate t = requireOwned(id);
        if (req.name() != null) t.setName(req.name());
        if (req.description() != null) t.setDescription(req.description());
        if (req.image() != null) t.setImage(req.image());
        if (req.command() != null) t.setCommand(req.command());
        if (req.defaultResourceSpec() != null) t.setDefaultResourceSpec(toJson(req.defaultResourceSpec()));
        if (req.defaultHyperparameters() != null) t.setDefaultHyperparameters(toJson(req.defaultHyperparameters()));
        templateMapper.update(t);
        return toResponse(templateMapper.selectById(id));
    }

    @Override
    @Transactional
    public Long instantiate(Long templateId, TrainingTemplateDtos.Instantiate req) {
        TrainingTemplate t = requireOwned(templateId);
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException(TrainingErrorCode.TEMPLATE_INSTANTIATE_FAILED,
                    "缺少租户上下文，无法实例化模板");
        }
        TrainingTask task = new TrainingTask();
        task.setTenantId(tenantId);
        task.setProjectId(req.projectId());
        task.setName(req.name());
        task.setModelVersionId(req.modelVersionId());
        task.setDatasetVersionId(req.datasetVersionId());
        // 请求体优先；缺省回退模板默认值
        task.setImage(req.image() != null ? req.image() : t.getImage());
        task.setCommand(req.command() != null ? req.command() : t.getCommand());
        task.setResourceSpec(req.resourceSpec() != null
                ? toJson(req.resourceSpec()) : t.getDefaultResourceSpec());
        task.setHyperparameters(req.hyperparameters() != null
                ? toJson(req.hyperparameters()) : t.getDefaultHyperparameters());
        task.setStatus("pending");
        task.setPriority(req.priority() == null ? 0 : req.priority());
        task.setProgress(0);
        task.setCreatedBy(UserContext.getUserId());
        taskMapper.insert(task);
        log.info("实例化模板 templateId={} → taskId={}", templateId, task.getId());
        return task.getId();
    }

    // ---- 辅助 ----

    private TrainingTemplate requireOwned(Long id) {
        TrainingTemplate t = templateMapper.selectById(id);
        if (t == null) throw new BusinessException(TrainingErrorCode.TEMPLATE_NOT_FOUND);
        if (!UserContext.isPlatformAdmin() && !Objects.equals(t.getTenantId(), UserContext.getTenantId())) {
            throw new BusinessException(TrainingErrorCode.TEMPLATE_NOT_FOUND);
        }
        return t;
    }

    private TrainingTemplateDtos.Response toResponse(TrainingTemplate t) {
        return new TrainingTemplateDtos.Response(
                t.getId(), t.getTenantId(), t.getName(), t.getDescription(), t.getImage(), t.getCommand(),
                fromJsonSpec(t.getDefaultResourceSpec()), fromJson(t.getDefaultHyperparameters()),
                t.getCreatedBy(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private Object fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (tools.jackson.core.JacksonException e) {
            return json;
        }
    }

    private ResourceSpec fromJsonSpec(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, ResourceSpec.class);
        } catch (tools.jackson.core.JacksonException e) {
            return null;
        }
    }
}
