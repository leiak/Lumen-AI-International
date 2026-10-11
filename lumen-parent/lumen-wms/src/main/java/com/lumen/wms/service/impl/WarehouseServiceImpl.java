package com.lumen.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.dto.CreateWarehouseRequest;
import com.lumen.wms.dto.UpdateWarehouseRequest;
import com.lumen.wms.dto.WarehouseVO;
import com.lumen.wms.entity.WmsWarehouse;
import com.lumen.wms.exception.WarehouseNotFoundException;
import com.lumen.wms.exception.WmsDuplicateCodeException;
import com.lumen.wms.mapper.WmsWarehouseMapper;
import com.lumen.wms.service.WarehouseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仓库主数据服务实现.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseServiceImpl implements WarehouseService {

    private final WmsWarehouseMapper mapper;

    @Override
    public PageResult<WmsWarehouse> page(long pageNum, long pageSize, String keyword, String status) {
        Page<WmsWarehouse> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WmsWarehouse> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(WmsWarehouse::getName, keyword).or().like(WmsWarehouse::getCode, keyword));
        }
        if (status != null && !status.isBlank()) {
            qw.eq(WmsWarehouse::getStatus, status);
        }
        qw.orderByDesc(WmsWarehouse::getId);
        Page<WmsWarehouse> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    public WmsWarehouse getById(Long id) {
        WmsWarehouse w = mapper.selectById(id);
        if (w == null) throw new WarehouseNotFoundException(id);
        return w;
    }

    @Override
    @Transactional
    public WmsWarehouse create(CreateWarehouseRequest req) {
        WmsWarehouse entity = new WmsWarehouse();
        BeanUtils.copyProperties(req, entity);
        if (entity.getTenantId() == null) {
            entity.setTenantId(TenantContext.require());
        }
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate warehouse code: {}", req.getCode());
            throw new WmsDuplicateCodeException("warehouse", req.getCode());
        }
        return entity;
    }

    @Override
    @Transactional
    public WmsWarehouse update(Long id, UpdateWarehouseRequest req) {
        WmsWarehouse existing = getById(id);
        BeanUtils.copyProperties(req, existing, "id", "tenantId", "createdAt");
        try {
            mapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate warehouse code on update: {}", req.getCode());
            throw new WmsDuplicateCodeException("warehouse", req.getCode());
        }
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        // 触发 NotFound 校验：未找到抛 WarehouseNotFoundException
        getById(id);
        mapper.deleteById(id);
    }

    @Override
    public WarehouseVO toVO(WmsWarehouse entity) {
        if (entity == null) return null;
        WarehouseVO vo = new WarehouseVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}