package com.aisys.model.service;

import com.aisys.model.dto.ModelTagDtos.ModelTagCreateRequest;
import com.aisys.model.dto.ModelTagDtos.ModelTagNode;

import java.util.List;

/**
 * 模型标签（树形）领域服务（DDD 5.3）。
 */
public interface ModelTagService {

    List<ModelTagNode> listTags();

    ModelTagNode createTag(ModelTagCreateRequest request);

    ModelTagNode updateTag(Long id, ModelTagCreateRequest request);

    void deleteTag(Long id);
}
