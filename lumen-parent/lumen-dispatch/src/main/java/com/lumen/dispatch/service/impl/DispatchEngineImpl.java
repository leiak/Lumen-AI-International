package com.lumen.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.DeliveryOrderDtos.AssignRequest;
import com.lumen.dispatch.engine.LoadingValidator;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.error.DispatchErrorCode;
import com.lumen.dispatch.exception.DispatchException;
import com.lumen.dispatch.exception.InsufficientVehicleException;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.mapper.WmsVehicleMapper;
import com.lumen.dispatch.service.DeliveryExceptionService;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.dispatch.service.DispatchEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 调度编排引擎实现 (Iteration 4.1 PR4).
 *
 * <p>{@code autoAssign} 按"同租户 + IDLE + 容量满足"三条件筛车，取 ID 升序第一条，
 * 走 {@link LoadingValidator#validate}，再调 {@link DeliveryOrderService#assign}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchEngineImpl implements DispatchEngine {

    private final WmsDeliveryOrderMapper orderMapper;
    private final WmsVehicleMapper vehicleMapper;
    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryExceptionService deliveryExceptionService;
    private final LoadingValidator loadingValidator;

    @Override
    public WmsDeliveryOrder autoAssign(Long orderId) {
        WmsDeliveryOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new DispatchException(DispatchErrorCode.DELIVERY_ORDER_NOT_FOUND,
                    "delivery order not found: id=" + orderId);
        }
        if (!DispatchConstants.DO_PENDING_DISPATCH.equals(order.getStatus())) {
            throw new DispatchException(DispatchErrorCode.ILLEGAL_DELIVERY_STATE,
                    "order not in PENDING_DISPATCH: id=" + orderId
                            + " status=" + order.getStatus());
        }

        // 同租户 + IDLE + 容量足够
        LambdaQueryWrapper<WmsVehicle> qw = new LambdaQueryWrapper<>();
        qw.eq(WmsVehicle::getTenantId, order.getTenantId());
        qw.eq(WmsVehicle::getStatus, DispatchConstants.VEH_IDLE);
        if (order.getTotalWeightKg() != null) {
            qw.ge(WmsVehicle::getMaxLoadKg, order.getTotalWeightKg());
        }
        if (order.getTotalVolumeM3() != null) {
            qw.ge(WmsVehicle::getMaxVolumeM3, order.getTotalVolumeM3());
        }
        qw.orderByAsc(WmsVehicle::getId);
        qw.last("LIMIT 1");

        List<WmsVehicle> candidates = vehicleMapper.selectList(qw);
        if (candidates.isEmpty()) {
            log.warn("autoAssign: no suitable vehicle for order {} (weight={} volume={})",
                    orderId, order.getTotalWeightKg(), order.getTotalVolumeM3());
            throw new InsufficientVehicleException(
                    "no available vehicle for order: id=" + orderId);
        }
        WmsVehicle vehicle = candidates.get(0);

        // 双重 LoadingValidator（service.assign 内部还会再调一次；这里先 fail-fast）
        loadingValidator.validate(vehicle, order);

        AssignRequest req = new AssignRequest();
        req.setVehicleId(vehicle.getId());
        req.setDriverId(vehicle.getDriverId());  // may be null — spec OK
        return deliveryOrderService.assign(orderId, req);
    }

    @Override
    public WmsDeliveryException disposeException(Long exceptionId) {
        return deliveryExceptionService.close(exceptionId);
    }
}
