package com.lumen.wms.service.impl;

import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.dto.ThresholdConfigVO;
import com.lumen.wms.dto.UpdateThresholdConfigRequest;
import com.lumen.wms.entity.WmsThresholdConfig;
import com.lumen.wms.exception.ThresholdConfigNotFoundException;
import com.lumen.wms.mapper.WmsThresholdConfigMapper;
import com.lumen.wms.service.ThresholdConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 阈值配置服务实现（T3.1：基础 CRUD + 自动初始化）.
 *
 * <p>每个租户至多一行配置（由 {@code uk_wms_threshold_tenant} 唯一约束保证）.
 * 首次访问该租户时 {@link #getOrInit()} 会插入全 0 默认值行.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThresholdConfigServiceImpl implements ThresholdConfigService {

    private final WmsThresholdConfigMapper mapper;

    @Override
    @Transactional
    public WmsThresholdConfig getOrInit() {
        Long tenantId = TenantContext.require();
        WmsThresholdConfig existing = mapper.selectByTenantId(tenantId);
        if (existing != null) {
            return existing;
        }

        // 自动初始化
        WmsThresholdConfig fresh = new WmsThresholdConfig();
        fresh.setTenantId(tenantId);
        fresh.setInbAmountThreshold(BigDecimal.ZERO);
        fresh.setInbQtyThreshold(BigDecimal.ZERO);
        fresh.setOutAmountThreshold(BigDecimal.ZERO);
        fresh.setOutQtyThreshold(BigDecimal.ZERO);
        fresh.setAdjAmountThreshold(BigDecimal.ZERO);
        fresh.setAdjQtyThreshold(BigDecimal.ZERO);
        fresh.setApprovalRequiredWhenExceed(1);
        try {
            mapper.insert(fresh);
            return fresh;
        } catch (DuplicateKeyException e) {
            log.debug("threshold config raced insert, re-select: tenantId={}", tenantId);
            WmsThresholdConfig raced = mapper.selectByTenantId(tenantId);
            if (raced == null) {
                throw new ThresholdConfigNotFoundException(tenantId);
            }
            return raced;
        }
    }

    @Override
    public WmsThresholdConfig get() {
        Long tenantId = TenantContext.require();
        WmsThresholdConfig cfg = mapper.selectByTenantId(tenantId);
        if (cfg == null) throw new ThresholdConfigNotFoundException(tenantId);
        return cfg;
    }

    @Override
    @Transactional
    public WmsThresholdConfig update(UpdateThresholdConfigRequest req) {
        WmsThresholdConfig existing = get();
        BeanUtils.copyProperties(req, existing, "id", "tenantId", "createdAt");
        mapper.updateById(existing);
        return mapper.selectById(existing.getId());
    }

    @Override
    public ThresholdConfigVO toVO(WmsThresholdConfig entity) {
        if (entity == null) return null;
        ThresholdConfigVO vo = new ThresholdConfigVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
