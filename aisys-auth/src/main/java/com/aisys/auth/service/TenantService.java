package com.aisys.auth.service;

import com.aisys.auth.constant.AuthErrorCode;
import com.aisys.auth.dto.TenantDtos;
import com.aisys.auth.entity.Tenant;
import com.aisys.auth.mapper.TenantMapper;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 租户管理（平台超管）：列表/创建/详情/更新（DDD 5.2.1）。 */
@Service
public class TenantService {

    private final TenantMapper tenantMapper;

    public TenantService(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<TenantDtos.Response> list(String keyword, int page, int size) {
        long total = tenantMapper.count(keyword);
        List<Tenant> rows = tenantMapper.page(keyword, Math.max(0, (page - 1) * size), size);
        return PageResult.of(rows.stream().map(this::toResponse).toList(), total, page, size);
    }

    @Transactional(readOnly = true)
    public TenantDtos.Response get(Long id) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) throw new BusinessException(AuthErrorCode.TENANT_NOT_FOUND);
        return toResponse(t);
    }

    @Transactional
    public TenantDtos.Response create(TenantDtos.Create req) {
        if (tenantMapper.selectByCode(req.code()) != null) {
            throw new BusinessException(AuthErrorCode.TENANT_CODE_EXISTS);
        }
        Tenant t = new Tenant();
        t.setName(req.name());
        t.setCode(req.code());
        t.setStatus(req.status() == null || req.status().isBlank() ? "active" : req.status());
        t.setMaxQuotaBytes(req.maxQuotaBytes());
        tenantMapper.insert(t);
        return toResponse(tenantMapper.selectById(t.getId()));
    }

    @Transactional
    public TenantDtos.Response update(Long id, TenantDtos.Create req) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) throw new BusinessException(AuthErrorCode.TENANT_NOT_FOUND);
        if (req.name() != null) t.setName(req.name());
        if (req.maxQuotaBytes() != null) t.setMaxQuotaBytes(req.maxQuotaBytes());
        if (req.status() != null) t.setStatus(req.status());
        tenantMapper.update(t);
        return toResponse(tenantMapper.selectById(id));
    }

    @Transactional
    public void delete(Long id) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) throw new BusinessException(AuthErrorCode.TENANT_NOT_FOUND);
        tenantMapper.deleteById(id);
    }

    private TenantDtos.Response toResponse(Tenant t) {
        return new TenantDtos.Response(t.getId(), t.getName(), t.getCode(), t.getStatus(),
                t.getStoragePoolId(), t.getMaxQuotaBytes(), t.getCreatedAt());
    }
}
