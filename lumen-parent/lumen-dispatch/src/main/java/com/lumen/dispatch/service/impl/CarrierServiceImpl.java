package com.lumen.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.CarrierDtos.CarrierVO;
import com.lumen.dispatch.dto.CarrierDtos.CreateCarrierRequest;
import com.lumen.dispatch.dto.CarrierDtos.QueryCarrierRequest;
import com.lumen.dispatch.dto.CarrierDtos.UpdateCarrierRequest;
import com.lumen.dispatch.entity.WmsCarrier;
import com.lumen.dispatch.exception.CarrierDuplicateCodeException;
import com.lumen.dispatch.exception.CarrierNotFoundException;
import com.lumen.dispatch.mapper.WmsCarrierMapper;
import com.lumen.dispatch.service.CarrierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 承运人主数据服务实现.
 *
 * <p>承运人主键外键被 {@code wms_vehicle.carrier_id} 引用；删除前应
 * 由前端 / 上层业务校验无在用车辆（PR3 不强制 FK 级联）.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierServiceImpl implements CarrierService {

    private final WmsCarrierMapper mapper;

    @Override
    public PageResult<WmsCarrier> page(long current, long size, QueryCarrierRequest query) {
        Page<WmsCarrier> p = new Page<>(current, size);
        LambdaQueryWrapper<WmsCarrier> qw = new LambdaQueryWrapper<>();
        if (query != null) {
            if (query.getStatus() != null && !query.getStatus().isBlank()) {
                qw.eq(WmsCarrier::getStatus, query.getStatus());
            }
            if (query.getCode() != null && !query.getCode().isBlank()) {
                qw.like(WmsCarrier::getCode, query.getCode());
            }
            if (query.getName() != null && !query.getName().isBlank()) {
                qw.like(WmsCarrier::getName, query.getName());
            }
        }
        qw.orderByDesc(WmsCarrier::getId);
        Page<WmsCarrier> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), current, size);
    }

    @Override
    public WmsCarrier get(Long id) {
        WmsCarrier c = mapper.selectById(id);
        if (c == null) throw new CarrierNotFoundException(id);
        return c;
    }

    @Override
    @Transactional
    public WmsCarrier create(CreateCarrierRequest req) {
        WmsCarrier entity = new WmsCarrier();
        BeanUtils.copyProperties(req, entity);
        if (entity.getTenantId() == null) {
            entity.setTenantId(TenantContext.require());
        }
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(DispatchConstants.STATUS_ACTIVE);
        }
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate carrier code: {}", req.getCode());
            throw new CarrierDuplicateCodeException(req.getCode());
        }
        return entity;
    }

    @Override
    @Transactional
    public WmsCarrier update(Long id, UpdateCarrierRequest req) {
        WmsCarrier existing = get(id);
        BeanUtils.copyProperties(req, existing, "id", "tenantId", "createdAt");
        try {
            mapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate carrier code on update: {}", req.getCode());
            throw new CarrierDuplicateCodeException(req.getCode());
        }
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        get(id);
        mapper.deleteById(id);
    }

    @Override
    public CarrierVO toVO(WmsCarrier entity) {
        if (entity == null) return null;
        CarrierVO vo = new CarrierVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}