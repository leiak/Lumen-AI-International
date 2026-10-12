package com.lumen.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest;
import com.lumen.dispatch.dto.VehicleDtos.QueryVehicleRequest;
import com.lumen.dispatch.dto.VehicleDtos.UpdateVehicleRequest;
import com.lumen.dispatch.dto.VehicleDtos.VehicleVO;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.error.DispatchErrorCode;
import com.lumen.dispatch.exception.DispatchException;
import com.lumen.dispatch.exception.VehicleDuplicatePlateException;
import com.lumen.dispatch.exception.VehicleNotFoundException;
import com.lumen.dispatch.mapper.WmsVehicleMapper;
import com.lumen.dispatch.service.VehicleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 车辆主数据服务实现 (Iteration 4.1 PR3).
 *
 * <p>基础 CRUD 与 WMS masterdata 服务同模式 (TenantContext 回填 +
 * {@code DuplicateKeyException} 捕获映射到 {@link VehicleDuplicatePlateException}).
 *
 * <p>freeze / unfreeze <strong>绕过</strong> {@code StateMachineEngine} 直接
 * setStatus. 原因：V20 seed 的 VEHICLE 状态机行 tenant_id=0 (global)，与
 * masterdata 的 tenant_id=N 租户体系在 MyBatis-Plus tenant 拦截器下不易
 * 干净共存；PR3 选用最简 direct-update 路径 (spec §10 PR3 注释允许).
 * PR5 引入 StateMachineEngine for VEHICLE 时再补 OUT→IDLE 行 + 完整
 * assertTransition 路径切换.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleServiceImpl implements VehicleService {

    private final WmsVehicleMapper mapper;

    @Override
    public PageResult<WmsVehicle> page(long current, long size, QueryVehicleRequest query) {
        Page<WmsVehicle> p = new Page<>(current, size);
        LambdaQueryWrapper<WmsVehicle> qw = new LambdaQueryWrapper<>();
        if (query != null) {
            if (query.getStatus() != null && !query.getStatus().isBlank()) {
                qw.eq(WmsVehicle::getStatus, query.getStatus());
            }
            if (query.getType() != null && !query.getType().isBlank()) {
                qw.eq(WmsVehicle::getType, query.getType());
            }
            if (query.getPlateNo() != null && !query.getPlateNo().isBlank()) {
                qw.like(WmsVehicle::getPlateNo, query.getPlateNo());
            }
        }
        qw.orderByDesc(WmsVehicle::getId);
        Page<WmsVehicle> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), current, size);
    }

    @Override
    public WmsVehicle get(Long id) {
        WmsVehicle v = mapper.selectById(id);
        if (v == null) throw new VehicleNotFoundException(id);
        return v;
    }

    @Override
    @Transactional
    public WmsVehicle create(CreateVehicleRequest req) {
        WmsVehicle entity = new WmsVehicle();
        BeanUtils.copyProperties(req, entity);
        if (entity.getTenantId() == null) {
            entity.setTenantId(TenantContext.require());
        }
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(DispatchConstants.VEH_IDLE);
        }
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate vehicle plate: {}", req.getPlateNo());
            throw new VehicleDuplicatePlateException(req.getPlateNo());
        }
        return entity;
    }

    @Override
    @Transactional
    public WmsVehicle update(Long id, UpdateVehicleRequest req) {
        WmsVehicle existing = get(id);
        BeanUtils.copyProperties(req, existing, "id", "tenantId", "createdAt");
        try {
            mapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate vehicle plate on update: {}", req.getPlateNo());
            throw new VehicleDuplicatePlateException(req.getPlateNo());
        }
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        get(id);  // throws NotFound if missing
        mapper.deleteById(id);
    }

    @Override
    @Transactional
    public WmsVehicle freeze(Long id) {
        WmsVehicle existing = get(id);
        String from = existing.getStatus();
        // Bypass state machine (see class docstring). V20 spec: 'out' transition
        // is allowed from IDLE/DISPATCHED/LOADING/ENROUTE/RETURNING → OUT.
        if (DispatchConstants.VEH_OUT.equals(from)) {
            log.warn("freeze rejected: vehicle {} already OUT", id);
            throw new DispatchException(DispatchErrorCode.ILLEGAL_VEHICLE_STATE,
                    "vehicle already OUT: id=" + id);
        }
        existing.setStatus(DispatchConstants.VEH_OUT);
        mapper.updateById(existing);
        log.info("vehicle {} frozen: {} -> {}", id, from, existing.getStatus());
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsVehicle unfreeze(Long id) {
        WmsVehicle existing = get(id);
        String from = existing.getStatus();
        if (!DispatchConstants.VEH_OUT.equals(from)) {
            log.warn("unfreeze rejected: vehicle {} not in OUT (current={})", id, from);
            throw new DispatchException(DispatchErrorCode.ILLEGAL_VEHICLE_STATE,
                    "vehicle not OUT: id=" + id + " status=" + from);
        }
        existing.setStatus(DispatchConstants.VEH_IDLE);
        mapper.updateById(existing);
        log.info("vehicle {} unfrozen: {} -> {}", id, from, existing.getStatus());
        return mapper.selectById(id);
    }

    @Override
    public VehicleVO toVO(WmsVehicle entity) {
        if (entity == null) return null;
        VehicleVO vo = new VehicleVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}