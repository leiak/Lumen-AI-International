package com.lumen.cs.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.cs.dto.SlaConfigDtos.CreateRequest;
import com.lumen.cs.dto.SlaConfigDtos.GetResponse;
import com.lumen.cs.dto.SlaConfigDtos.ListRequest;
import com.lumen.cs.dto.SlaConfigDtos.ListResponse;
import com.lumen.cs.dto.SlaConfigDtos.UpdateRequest;
import com.lumen.cs.entity.SlaConfig;
import com.lumen.cs.exception.CsErrorCode;
import com.lumen.cs.exception.CsException;
import com.lumen.cs.mapper.SlaConfigMapper;
import com.lumen.cs.service.SlaConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SLA 配置服务实现 (Iteration 4.2 PR2).
 *
 * <p>resolve_minutes >= response_minutes 是核心业务校验；
 * UNIQUE KEY (tenant_id, severity, level, deleted) 重复由 service 翻译为
 * {@link CsErrorCode#DUPLICATE_SLA_CONFIG}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaConfigServiceImpl implements SlaConfigService {

    private final SlaConfigMapper mapper;

    @Override
    @Transactional
    public GetResponse create(Long tenantId, CreateRequest req) {
        if (req.resolveMinutes() < req.responseMinutes()) {
            throw new CsException(CsErrorCode.INVALID_RESOLVE_MINUTES);
        }
        SlaConfig row = new SlaConfig();
        row.setTenantId(tenantId);
        row.setSeverity(req.severity());
        row.setLevel(req.level());
        row.setResponseMinutes(req.responseMinutes());
        row.setResolveMinutes(req.resolveMinutes());
        row.setEscalateToLevel(req.escalateToLevel());
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate sla config tenant={} severity={} level={}",
                tenantId, req.severity(), req.level());
            throw new CsException(CsErrorCode.DUPLICATE_SLA_CONFIG);
        }
        return toDto(row);
    }

    @Override
    @Transactional
    public GetResponse update(Long tenantId, Long id, UpdateRequest req) {
        if (req.resolveMinutes() < req.responseMinutes()) {
            throw new CsException(CsErrorCode.INVALID_RESOLVE_MINUTES);
        }
        SlaConfig row = mustFind(tenantId, id);
        row.setResponseMinutes(req.responseMinutes());
        row.setResolveMinutes(req.resolveMinutes());
        row.setEscalateToLevel(req.escalateToLevel());
        mapper.updateById(row);
        // refresh to get updated timestamps
        return toDto(mapper.selectById(row.getId()));
    }

    @Override
    @Transactional
    public void delete(Long tenantId, Long id) {
        SlaConfig row = mustFind(tenantId, id);
        mapper.deleteById(row.getId());
    }

    @Override
    public GetResponse get(Long tenantId, Long id) {
        return toDto(mustFind(tenantId, id));
    }

    @Override
    public ListResponse list(Long tenantId, ListRequest req) {
        int pageNum = req.page() == null || req.page() < 1 ? 1 : req.page();
        int pageSize = req.size() == null || req.size() < 1 ? 20 : Math.min(req.size(), 200);
        Page<SlaConfig> page = Page.of(pageNum, pageSize);
        LambdaQueryWrapper<SlaConfig> q = new LambdaQueryWrapper<SlaConfig>()
            .eq(SlaConfig::getTenantId, tenantId)
            .eq(req.severity() != null && !req.severity().isBlank(), SlaConfig::getSeverity, req.severity())
            .eq(req.level() != null && !req.level().isBlank(), SlaConfig::getLevel, req.level())
            .orderByAsc(SlaConfig::getSeverity, SlaConfig::getLevel);
        Page<SlaConfig> result = mapper.selectPage(page, q);
        List<GetResponse> items = result.getRecords().stream().map(this::toDto).toList();
        return new ListResponse(items, result.getTotal());
    }

    private SlaConfig mustFind(Long tenantId, Long id) {
        SlaConfig row = mapper.selectById(id);
        if (row == null || !row.getTenantId().equals(tenantId)) {
            throw new CsException(CsErrorCode.SLA_CONFIG_NOT_FOUND);
        }
        return row;
    }

    private GetResponse toDto(SlaConfig row) {
        return new GetResponse(
            row.getId(), row.getTenantId(),
            row.getSeverity(), row.getLevel(),
            row.getResponseMinutes(), row.getResolveMinutes(), row.getEscalateToLevel(),
            row.getCreatedAt(), row.getUpdatedAt()
        );
    }
}