package com.lumen.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.DeliveryOrderDtos.ArriveRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.AssignRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.CancelRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.CreateDeliveryOrderRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.DepartRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.LoadRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.QueryDeliveryOrderRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.RaiseExceptionRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.SignRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.UpdateDeliveryOrderRequest;
import com.lumen.dispatch.engine.LoadingValidator;
import com.lumen.dispatch.engine.RoutingEngine;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsDeliveryStop;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.exception.DeliveryOrderNotFoundException;
import com.lumen.dispatch.exception.DeliveryStopNotFoundException;
import com.lumen.dispatch.exception.IllegalDeliveryOrderStateException;
import com.lumen.dispatch.exception.VehicleNotFoundException;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.mapper.WmsDeliveryStopMapper;
import com.lumen.dispatch.mapper.WmsVehicleMapper;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.extension.state.StateMachineEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 配送订单服务实现 (Iteration 4.1 PR4).
 *
 * <p>8 个状态机迁移全部走 {@link StateMachineEngine#assertTransition},
 * 涉及车辆状态的 {@code assign} 同步触发 VEHICLE SM dispatch.
 *
 * <p>{@code raiseException} 在事务内同时新建 WmsDeliveryException(status=OPEN)
 * 并把 order.exception_id 反向写回订单行（便于反查）.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryOrderServiceImpl implements DeliveryOrderService {

    private final WmsDeliveryOrderMapper orderMapper;
    private final WmsDeliveryStopMapper stopMapper;
    private final WmsDeliveryExceptionMapper exceptionMapper;
    private final WmsVehicleMapper vehicleMapper;
    private final LoadingValidator loadingValidator;
    private final RoutingEngine routingEngine;
    private final StateMachineEngine stateMachineEngine;

    // ================================================================
    // CRUD
    // ================================================================

    @Override
    public PageResult<WmsDeliveryOrder> page(long current, long size, QueryDeliveryOrderRequest query) {
        Page<WmsDeliveryOrder> p = new Page<>(current, size);
        LambdaQueryWrapper<WmsDeliveryOrder> qw = new LambdaQueryWrapper<>();
        if (query != null) {
            if (query.getStatus() != null && !query.getStatus().isBlank()) {
                qw.eq(WmsDeliveryOrder::getStatus, query.getStatus());
            }
            if (query.getVehicleId() != null) {
                qw.eq(WmsDeliveryOrder::getVehicleId, query.getVehicleId());
            }
            if (query.getDriverId() != null) {
                qw.eq(WmsDeliveryOrder::getDriverId, query.getDriverId());
            }
            if (query.getWarehouseId() != null) {
                qw.eq(WmsDeliveryOrder::getWarehouseId, query.getWarehouseId());
            }
        }
        qw.orderByDesc(WmsDeliveryOrder::getId);
        Page<WmsDeliveryOrder> res = orderMapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), current, size);
    }

    @Override
    public WmsDeliveryOrder get(Long id) {
        WmsDeliveryOrder o = orderMapper.selectById(id);
        if (o == null) throw new DeliveryOrderNotFoundException(id);
        return o;
    }

    @Override
    public List<WmsDeliveryStop> listStops(Long orderId) {
        // triggers existence check
        get(orderId);
        return stopMapper.selectList(
                new LambdaQueryWrapper<WmsDeliveryStop>()
                        .eq(WmsDeliveryStop::getOrderId, orderId)
                        .orderByAsc(WmsDeliveryStop::getStopNo));
    }

    @Override
    @Transactional
    public WmsDeliveryOrder create(CreateDeliveryOrderRequest req) {
        WmsDeliveryOrder order = new WmsDeliveryOrder();
        BeanUtils.copyProperties(req, order);
        if (order.getTenantId() == null) {
            order.setTenantId(TenantContext.require());
        }
        if (order.getSourceType() == null || order.getSourceType().isBlank()) {
            order.setSourceType("OUTBOUND");
        }
        if (order.getNo() == null || order.getNo().isBlank()) {
            // mock number — 真实 PR5 走 NumberGenerator.generate("delivery_order")
            order.setNo("DO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        if (order.getStatus() == null || order.getStatus().isBlank()) {
            order.setStatus(DispatchConstants.DO_PENDING_DISPATCH);
        }
        if (order.getSignRequired() == null) {
            order.setSignRequired(1);
        }
        if (order.getTotalWeightKg() == null) order.setTotalWeightKg(BigDecimal.ZERO);
        if (order.getTotalVolumeM3() == null) order.setTotalVolumeM3(BigDecimal.ZERO);
        if (order.getStopCount() == null) order.setStopCount(0);
        orderMapper.insert(order);
        log.info("Delivery order created: id={} no={} source={}",
                order.getId(), order.getNo(), order.getSourceNo());
        return order;
    }

    @Override
    @Transactional
    public WmsDeliveryOrder update(Long id, UpdateDeliveryOrderRequest req) {
        WmsDeliveryOrder existing = get(id);
        // 不可改字段：no / status / sourceNo / vehicleId / driverId / carrierId
        BeanUtils.copyProperties(req, existing,
                "id", "tenantId", "createdAt", "no", "status",
                "sourceNo", "sourceType", "vehicleId", "driverId",
                "carrierId", "actualDepartAt", "arrivedAt", "deliveredAt",
                "signPhotoUrl", "exceptionId");
        orderMapper.updateById(existing);
        return orderMapper.selectById(id);
    }

    // ================================================================
    // State machine
    // ================================================================

    @Override
    @Transactional
    public WmsDeliveryOrder assign(Long id, AssignRequest req) {
        WmsDeliveryOrder order = get(id);
        String from = order.getStatus();
        String to = DispatchConstants.DO_DISPATCHED;
        stateMachineEngine.assertTransition(DispatchConstants.SM_DISPATCH_ORDER, from, to);

        // vehicle / driver validation
        WmsVehicle vehicle = vehicleMapper.selectById(req.getVehicleId());
        if (vehicle == null) throw new VehicleNotFoundException(req.getVehicleId());
        loadingValidator.validate(vehicle, order);

        // 车辆状态机: IDLE/DISPATCHED/etc. → DISPATCHED (action=dispatch)
        String vehFrom = vehicle.getStatus();
        if (!DispatchConstants.VEH_DISPATCHED.equals(vehFrom)) {
            try {
                stateMachineEngine.assertTransition(
                        DispatchConstants.SM_VEHICLE, vehFrom, DispatchConstants.VEH_DISPATCHED);
            } catch (Exception smEx) {
                // m-3: log cause before rethrow, 便于排查"车辆状态机失败"的具体原因.
                log.warn("VEHICLE state machine failed for id={}: {}", vehicle.getId(), smEx.getMessage());
                throw new IllegalDeliveryOrderStateException(
                        "vehicle " + vehicle.getId() + " not dispatchable from " + vehFrom);
            }
            vehicle.setStatus(DispatchConstants.VEH_DISPATCHED);
            vehicleMapper.updateById(vehicle);
        }

        order.setVehicleId(req.getVehicleId());
        order.setDriverId(req.getDriverId());
        order.setStatus(to);
        orderMapper.updateById(order);

        // 排线：mock 实现仅按 stop_no 排序，写回 stops 的"已排线"状态。
        // 当前 schema 无 route_sequence 列，setId 后即暗示；保留 hook 给后续实现.
        List<WmsDeliveryStop> stops = stopMapper.selectList(
                new LambdaQueryWrapper<WmsDeliveryStop>().eq(WmsDeliveryStop::getOrderId, id));
        if (!stops.isEmpty()) {
            // 调用 hook: 确认返回顺序非空即为可排线；若失败降级（不抛业务异常）
            List<Long> plan = routingEngine.plan(order.getWarehouseId(), stops);
            log.debug("RoutingEngine.plan returned {} stops for order {}", plan.size(), id);
        }

        log.info("Delivery order {} assigned: vehicle={} driver={} {} -> {}",
                id, req.getVehicleId(), req.getDriverId(), from, to);
        return orderMapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsDeliveryOrder load(Long id, LoadRequest req) {
        WmsDeliveryOrder order = get(id);
        String from = order.getStatus();
        String to = DispatchConstants.DO_LOADED;
        stateMachineEngine.assertTransition(DispatchConstants.SM_DISPATCH_ORDER, from, to);

        order.setStatus(to);
        orderMapper.updateById(order);
        log.info("Delivery order {} loaded: {} -> {}", id, from, to);
        return orderMapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsDeliveryOrder depart(Long id, DepartRequest req) {
        WmsDeliveryOrder order = get(id);
        String from = order.getStatus();
        String to = DispatchConstants.DO_ENROUTE;
        stateMachineEngine.assertTransition(DispatchConstants.SM_DISPATCH_ORDER, from, to);

        order.setStatus(to);
        order.setActualDepartAt(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("Delivery order {} departed: {} -> {}", id, from, to);
        return orderMapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsDeliveryOrder arrive(Long id, ArriveRequest req) {
        WmsDeliveryOrder order = get(id);
        String from = order.getStatus();
        String to = DispatchConstants.DO_ARRIVED;
        stateMachineEngine.assertTransition(DispatchConstants.SM_DISPATCH_ORDER, from, to);

        WmsDeliveryStop stop = stopMapper.selectById(req.getStopId());
        if (stop == null || !stop.getOrderId().equals(id)) {
            throw new DeliveryStopNotFoundException(req.getStopId());
        }
        stop.setArrivedAt(req.getArrivedAt() != null ? req.getArrivedAt() : LocalDateTime.now());
        stop.setStatus("ARRIVED");
        stopMapper.updateById(stop);

        order.setStatus(to);
        orderMapper.updateById(order);
        log.info("Delivery order {} arrived: stop={} {} -> {}", id, stop.getId(), from, to);
        return orderMapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsDeliveryOrder sign(Long id, SignRequest req) {
        WmsDeliveryOrder order = get(id);
        String from = order.getStatus();
        String to = DispatchConstants.DO_SIGNED;
        stateMachineEngine.assertTransition(DispatchConstants.SM_DISPATCH_ORDER, from, to);

        WmsDeliveryStop stop = stopMapper.selectById(req.getStopId());
        if (stop == null || !stop.getOrderId().equals(id)) {
            throw new DeliveryStopNotFoundException(req.getStopId());
        }
        stop.setCompletedAt(LocalDateTime.now());
        stop.setStatus("COMPLETED");
        stopMapper.updateById(stop);

        order.setStatus(to);
        order.setSignPhotoUrl(req.getSignPhotoUrl());
        order.setDeliveredAt(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("Delivery order {} signed: stop={} {} -> {}", id, stop.getId(), from, to);
        return orderMapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsDeliveryOrder cancel(Long id, CancelRequest req) {
        WmsDeliveryOrder order = get(id);
        String from = order.getStatus();
        String to = DispatchConstants.DO_CANCELLED;
        stateMachineEngine.assertTransition(DispatchConstants.SM_DISPATCH_ORDER, from, to);

        order.setStatus(to);
        order.setRemark((order.getRemark() == null ? "" : order.getRemark() + " | ")
                + "CANCELLED: " + req.getReason());
        orderMapper.updateById(order);
        log.info("Delivery order {} cancelled: {} -> {} reason={}", id, from, to, req.getReason());
        return orderMapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsDeliveryException raiseException(Long id, RaiseExceptionRequest req) {
        WmsDeliveryOrder order = get(id);
        String from = order.getStatus();
        String to = DispatchConstants.DO_EXCEPTION;
        stateMachineEngine.assertTransition(DispatchConstants.SM_DISPATCH_ORDER, from, to);

        // 1) 创建异常工单
        WmsDeliveryException ex = new WmsDeliveryException();
        ex.setTenantId(order.getTenantId());
        ex.setOrderId(id);
        ex.setType(req.getType());
        ex.setStatus(DispatchConstants.EX_OPEN);
        ex.setDescription(req.getDescription());
        exceptionMapper.insert(ex);

        // 2) 同步订单状态 + exception_id
        order.setStatus(to);
        order.setExceptionId(ex.getId());
        orderMapper.updateById(order);

        log.info("Delivery order {} -> EXCEPTION: exceptionId={} type={}",
                id, ex.getId(), req.getType());
        return ex;
    }
}
