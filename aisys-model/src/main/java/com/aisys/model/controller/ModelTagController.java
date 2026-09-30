package com.aisys.model.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.model.dto.ModelTagDtos.ModelTagCreateRequest;
import com.aisys.model.dto.ModelTagDtos.ModelTagNode;
import com.aisys.model.service.ModelTagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型标签（树形）接口（DDD 5.3.1）。
 * <p>路径前缀 /api/v1/model-tags。tenant_id 由 RLS 隐式过滤；GET 返回组装好的树。
 */
@RestController
@RequestMapping("/api/v1/model-tags")
public class ModelTagController {

    private final ModelTagService tagService;

    public ModelTagController(ModelTagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public ApiResponse<List<ModelTagNode>> list() {
        return ApiResponse.success(tagService.listTags());
    }

    @PostMapping
    @AuditLog(action = "CREATE", resource = "MODEL_TAG", description = "创建模型标签",
            resourceId = "#result.data.id")
    public ApiResponse<ModelTagNode> create(@Valid @RequestBody ModelTagCreateRequest request) {
        return ApiResponse.success(tagService.createTag(request));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "UPDATE", resource = "MODEL_TAG", description = "更新模型标签",
            resourceId = "#id")
    public ApiResponse<ModelTagNode> update(@PathVariable Long id,
                                            @Valid @RequestBody ModelTagCreateRequest request) {
        return ApiResponse.success(tagService.updateTag(id, request));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "DELETE", resource = "MODEL_TAG", description = "删除模型标签",
            resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tagService.deleteTag(id);
        return ApiResponse.success();
    }
}
