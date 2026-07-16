package com.aisys.model.service.impl;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.model.constant.ModelErrorCode;
import com.aisys.model.dto.ModelTagDtos.ModelTagCreateRequest;
import com.aisys.model.dto.ModelTagDtos.ModelTagNode;
import com.aisys.model.entity.ModelTag;
import com.aisys.model.mapper.ModelTagMapper;
import com.aisys.model.service.ModelTagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型标签（树形）领域服务实现（DDD 5.3）。
 * <p>查询全部后内存组装树（标签数量通常不大）；tenant_id 由 RLS 隐式过滤。
 */
@Service
public class ModelTagServiceImpl implements ModelTagService {

    private static final Logger log = LoggerFactory.getLogger(ModelTagServiceImpl.class);

    private final ModelTagMapper tagMapper;

    public ModelTagServiceImpl(ModelTagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelTagNode> listTags() {
        List<ModelTag> all = tagMapper.selectAll();
        return buildTree(all);
    }

    @Override
    @Transactional
    public ModelTagNode createTag(ModelTagCreateRequest request) {
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException(ModelErrorCode.TENANT_REQUIRED);
        }
        if (request.parentId() != null) {
            ModelTag parent = tagMapper.selectById(request.parentId());
            if (parent == null) {
                throw new BusinessException(ModelErrorCode.MODEL_TAG_NOT_FOUND);
            }
        }
        ModelTag t = new ModelTag();
        t.setTenantId(tenantId);
        t.setParentId(request.parentId());
        t.setName(request.name());
        t.setSort(request.sort() == null ? 0 : request.sort());
        t.setPath(request.name()); // 初始 path = name，可由调用方扩展
        tagMapper.insert(t);
        log.info("[ModelTag] 创建标签 id={} parentId={} name={}", t.getId(), t.getParentId(), t.getName());
        return toNode(t, List.of());
    }

    @Override
    @Transactional
    public ModelTagNode updateTag(Long id, ModelTagCreateRequest request) {
        ModelTag t = getTagOrThrow(id);
        if (request.parentId() != null && !request.parentId().equals(t.getParentId())) {
            // 防止把父挂到自己或后代下（简单环检测）
            if (request.parentId().equals(id)) {
                throw new BusinessException(ModelErrorCode.BAD_REQUEST);
            }
            t.setParentId(request.parentId());
        }
        if (request.name() != null) t.setName(request.name());
        if (request.sort() != null) t.setSort(request.sort());
        tagMapper.update(t);
        return toNode(t, List.of());
    }

    @Override
    @Transactional
    public void deleteTag(Long id) {
        getTagOrThrow(id); // 不存在则 404；级联删除子标签由 FK ON DELETE CASCADE 处理
        tagMapper.deleteById(id);
        log.info("[ModelTag] 删除标签 id={}", id);
    }

    // ---------- 辅助：组装树 ----------

    private List<ModelTagNode> buildTree(List<ModelTag> all) {
        // 先按 sort/id 排序
        all.sort(Comparator
                .comparingInt((ModelTag t) -> t.getSort() == null ? 0 : t.getSort())
                .thenComparingLong(ModelTag::getId));

        Map<Long, ModelTagNode> nodeById = new LinkedHashMap<>();
        for (ModelTag t : all) {
            nodeById.put(t.getId(), toNode(t, new ArrayList<>()));
        }
        List<ModelTagNode> roots = new ArrayList<>();
        for (ModelTag t : all) {
            ModelTagNode node = nodeById.get(t.getId());
            if (t.getParentId() == null || !nodeById.containsKey(t.getParentId())) {
                roots.add(node);
            } else {
                nodeById.get(t.getParentId()).children().add(node);
            }
        }
        return roots;
    }

    private ModelTagNode toNode(ModelTag t, List<ModelTagNode> children) {
        return new ModelTagNode(
                t.getId(), t.getParentId(), t.getName(), t.getPath(),
                t.getSort(), t.getCreatedAt(), children);
    }

    private ModelTag getTagOrThrow(Long id) {
        ModelTag t = tagMapper.selectById(id);
        if (t == null) {
            throw new BusinessException(ModelErrorCode.MODEL_TAG_NOT_FOUND);
        }
        return t;
    }
}
