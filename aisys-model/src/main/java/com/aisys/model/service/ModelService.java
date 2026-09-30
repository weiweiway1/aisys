package com.aisys.model.service;

import com.aisys.common.core.response.PageResult;
import com.aisys.model.dto.ModelDtos.ModelCreateRequest;
import com.aisys.model.dto.ModelDtos.ModelDetailResponse;
import com.aisys.model.dto.ModelDtos.ModelListResponse;
import com.aisys.model.dto.ModelDtos.ModelQueryRequest;
import com.aisys.model.dto.ModelDtos.ModelUpdateRequest;

/**
 * 模型领域服务（DDD 5.3）。
 */
public interface ModelService {

    PageResult<ModelListResponse> listModels(ModelQueryRequest request);

    ModelDetailResponse getModel(Long id);

    ModelDetailResponse createModel(ModelCreateRequest request);

    ModelDetailResponse updateModel(Long id, ModelUpdateRequest request);

    void deleteModel(Long id);

    void publishModel(Long id);

    void deprecateModel(Long id);

    void archiveModel(Long id);
}
