package com.aisys.storage.service;

import com.aisys.common.core.response.PageResult;
import com.aisys.storage.dto.StoragePoolDtos;
import com.aisys.storage.entity.StoragePool;

/** 存储池管理服务（平台共享资源，DDD 5.8.1）。 */
public interface StoragePoolService {

    PageResult<StoragePoolDtos.Response> list(String keyword, int page, int size);

    StoragePoolDtos.Response get(Long id);

    StoragePoolDtos.Response create(StoragePoolDtos.Create req);

    StoragePoolDtos.Response update(Long id, StoragePoolDtos.Create req);

    void delete(Long id);

    StoragePoolDtos.Usage usage(Long id);

    /** 按名称查找原始实体（供内部组件使用）。 */
    StoragePool getByName(String name);

    /** 按主键查找原始实体。 */
    StoragePool getById(Long id);

    /** 默认池保障：若不存在则创建。返回 default 池。 */
    StoragePool ensureDefaultPool();
}
