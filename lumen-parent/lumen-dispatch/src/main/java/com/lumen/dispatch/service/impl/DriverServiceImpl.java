package com.lumen.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest;
import com.lumen.dispatch.dto.DriverDtos.DriverVO;
import com.lumen.dispatch.dto.DriverDtos.QueryDriverRequest;
import com.lumen.dispatch.dto.DriverDtos.UpdateDriverRequest;
import com.lumen.dispatch.entity.WmsDriver;
import com.lumen.dispatch.exception.DriverDuplicatePhoneException;
import com.lumen.dispatch.exception.DriverNotFoundException;
import com.lumen.dispatch.mapper.WmsDriverMapper;
import com.lumen.dispatch.service.DriverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 司机主数据服务实现.
 *
 * <p>{@code status} 字段 DB CHECK 约束为 ACTIVE/SUSPENDED 二选一，
 * service 不引入 {@code t_state_transition} 行（V20 未 seed DRIVER 状态机），
 * suspend / resume 直接 setStatus.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverServiceImpl implements DriverService {

    private final WmsDriverMapper mapper;

    @Override
    public PageResult<WmsDriver> page(long current, long size, QueryDriverRequest query) {
        Page<WmsDriver> p = new Page<>(current, size);
        LambdaQueryWrapper<WmsDriver> qw = new LambdaQueryWrapper<>();
        if (query != null) {
            if (query.getStatus() != null && !query.getStatus().isBlank()) {
                qw.eq(WmsDriver::getStatus, query.getStatus());
            }
            if (query.getName() != null && !query.getName().isBlank()) {
                qw.like(WmsDriver::getName, query.getName());
            }
        }
        qw.orderByDesc(WmsDriver::getId);
        Page<WmsDriver> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), current, size);
    }

    @Override
    public WmsDriver get(Long id) {
        WmsDriver d = mapper.selectById(id);
        if (d == null) throw new DriverNotFoundException(id);
        return d;
    }

    @Override
    @Transactional
    public WmsDriver create(CreateDriverRequest req) {
        WmsDriver entity = new WmsDriver();
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
            log.warn("duplicate driver phone: {}", req.getPhone());
            throw new DriverDuplicatePhoneException(req.getPhone());
        }
        return entity;
    }

    @Override
    @Transactional
    public WmsDriver update(Long id, UpdateDriverRequest req) {
        WmsDriver existing = get(id);
        BeanUtils.copyProperties(req, existing, "id", "tenantId", "createdAt");
        try {
            mapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate driver phone on update: {}", req.getPhone());
            throw new DriverDuplicatePhoneException(req.getPhone());
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
    @Transactional
    public WmsDriver suspend(Long id) {
        WmsDriver existing = get(id);
        existing.setStatus(DispatchConstants.STATUS_SUSPENDED);
        mapper.updateById(existing);
        log.info("driver {} suspended", id);
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsDriver resume(Long id) {
        WmsDriver existing = get(id);
        existing.setStatus(DispatchConstants.STATUS_ACTIVE);
        mapper.updateById(existing);
        log.info("driver {} resumed", id);
        return mapper.selectById(id);
    }

    @Override
    public DriverVO toVO(WmsDriver entity) {
        if (entity == null) return null;
        DriverVO vo = new DriverVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}