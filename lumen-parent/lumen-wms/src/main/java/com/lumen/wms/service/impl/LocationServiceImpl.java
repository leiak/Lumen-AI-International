package com.lumen.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.dto.CreateLocationRequest;
import com.lumen.wms.dto.LocationVO;
import com.lumen.wms.dto.UpdateLocationRequest;
import com.lumen.wms.entity.WmsLocation;
import com.lumen.wms.exception.LocationNotFoundException;
import com.lumen.wms.exception.WmsDuplicateCodeException;
import com.lumen.wms.mapper.WmsLocationMapper;
import com.lumen.wms.mapper.WmsWarehouseMapper;
import com.lumen.wms.service.LocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 库位主数据服务实现.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationServiceImpl implements LocationService {

    private final WmsLocationMapper mapper;
    private final WmsWarehouseMapper warehouseMapper;

    @Override
    public PageResult<WmsLocation> page(long pageNum, long pageSize, Long warehouseId, String keyword, String status) {
        Page<WmsLocation> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WmsLocation> qw = new LambdaQueryWrapper<>();
        if (warehouseId != null) {
            qw.eq(WmsLocation::getWarehouseId, warehouseId);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(WmsLocation::getCode, keyword).or().like(WmsLocation::getZone, keyword));
        }
        if (status != null && !status.isBlank()) {
            qw.eq(WmsLocation::getStatus, status);
        }
        qw.orderByDesc(WmsLocation::getId);
        Page<WmsLocation> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    public WmsLocation getById(Long id) {
        WmsLocation loc = mapper.selectById(id);
        if (loc == null) throw new LocationNotFoundException(id);
        return loc;
    }

    @Override
    @Transactional
    public WmsLocation create(CreateLocationRequest req) {
        WmsLocation entity = new WmsLocation();
        BeanUtils.copyProperties(req, entity);
        if (entity.getTenantId() == null) {
            entity.setTenantId(TenantContext.require());
        }
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate location code: warehouseId={}, code={}", req.getWarehouseId(), req.getCode());
            throw new WmsDuplicateCodeException("location", req.getCode());
        }
        return entity;
    }

    @Override
    @Transactional
    public WmsLocation update(Long id, UpdateLocationRequest req) {
        WmsLocation existing = getById(id);
        BeanUtils.copyProperties(req, existing, "id", "tenantId", "createdAt");
        try {
            mapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate location code on update: {}", req.getCode());
            throw new WmsDuplicateCodeException("location", req.getCode());
        }
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getById(id);
        mapper.deleteById(id);
    }

    @Override
    public List<WmsLocation> listByWarehouse(Long warehouseId) {
        return mapper.selectList(
                new LambdaQueryWrapper<WmsLocation>()
                        .eq(WmsLocation::getWarehouseId, warehouseId)
                        .orderByAsc(WmsLocation::getCode));
    }

    @Override
    public LocationVO toVO(WmsLocation entity) {
        if (entity == null) return null;
        LocationVO vo = new LocationVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}