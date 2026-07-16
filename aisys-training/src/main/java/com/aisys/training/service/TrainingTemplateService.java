package com.aisys.training.service;

import com.aisys.common.core.response.PageResult;
import com.aisys.training.dto.TrainingTemplateDtos;

/**
 * 训练模板领域服务（DDD 5.5）。模板 CRUD + 实例化为任务。
 */
public interface TrainingTemplateService {

    PageResult<TrainingTemplateDtos.Response> list(String keyword, int page, int size);

    TrainingTemplateDtos.Response get(Long id);

    TrainingTemplateDtos.Response create(TrainingTemplateDtos.Create req);

    TrainingTemplateDtos.Response update(Long id, TrainingTemplateDtos.Update req);

    /** 实例化模板为训练任务（status=pending）。返回新任务 id。 */
    Long instantiate(Long templateId, TrainingTemplateDtos.Instantiate req);
}
