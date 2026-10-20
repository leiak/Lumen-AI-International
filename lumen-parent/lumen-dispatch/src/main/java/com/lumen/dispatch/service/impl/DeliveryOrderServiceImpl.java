package com.lumen.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.dto.TrackQuery;
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
import com.lumen.dispatch.dto.DeliveryOrderDtos.ResolveExceptionRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.SignRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.UpdateDeliveryOrderRequest;
import com.lumen.dispatch.engine.LoadingValidator;
import com.lumen.dispatch.engine.RoutingEngine;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsDeliveryStop;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.error.DispatchErrorCode;
import com.lumen.dispatch.event.DeliveryExceptionStateChangedEvent;
import com.lumen.dispatch.event.DeliveryOrderStateChangedEvent;
import com.lumen.dispatch.exception.DeliveryOrderNotFoundException;
import com.lumen.dispatch.exception.DeliveryStopNotFoundException;
import com.lumen.dispatch.exception.DispatchException;
import com.lumen.dispatch.exception.IllegalDeliveryOrderStateException;
import com.lumen.dispatch.exception.VehicleNotFoundException;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.mapper.WmsDeliveryStopMapper;
import com.lumen.dispatch.mapper.WmsVehicleMapper;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.extension.outbox.EventBus;
import com.lumen.extension.state.StateMachineEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 配送订单服务实现 (Iteration 4.1 PR4 + 4.1.x PR1).
 *
 * <p>8 个状态机迁移全部走 {@link StateMachineEngine#assertTransition},
 * 涉及车辆状态的 {@code assign} 同步触发 VEHICLE SM dispatch.
 *
 * <p>{@code raiseException} 在事务内同时新建 WmsDeliveryException(status=OPEN)
 * 并把 order.exception_id 反向写回订单行（便于反查）.
 *
 * <p>{@code resolveException} (4.1.x PR1) 在 EXCEPTION 处置阶段分流:
 * RE_DISPATCH 走 reDispatch 迁移 + 同步触发 VEHICLE SM dispatch;
 * CANCEL 走 exceptionCancel 迁移并把 reason 拼到 remark.
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
    private final EventBus eventBus;

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

        // PR2: 发布"初始进入 PENDING_DISPATCH"事件 — from=null 表示初始化.
        // 与 9 个状态机迁移一起凑齐 6 个 delivery_order.state_changed 事件
        // (create + assign + load + depart + arrive + sign).
        eventBus.publish(DeliveryOrderStateChangedEvent.of(order, null, DispatchConstants.DO_PENDING_DISPATCH, "create"));

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

        // PR2: 发布"分配车辆"事件 (PENDING_DISPATCH → DISPATCHED).
        eventBus.publish(DeliveryOrderStateChangedEvent.of(order, from, to, "dispatch"));

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

        // PR2: 发布"装车"事件 (DISPATCHED → LOADED).
        eventBus.publish(DeliveryOrderStateChangedEvent.of(order, from, to, "load"));

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

        // PR2: 发布"发车"事件 (LOADED → ENROUTE).
        eventBus.publish(DeliveryOrderStateChangedEvent.of(order, from, to, "depart"));

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

        // PR2: 发布"到站"事件 (ENROUTE → ARRIVED).
        eventBus.publish(DeliveryOrderStateChangedEvent.of(order, from, to, "arrive"));

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

        // PR2: 发布"签收"事件 (ARRIVED → SIGNED).
        eventBus.publish(DeliveryOrderStateChangedEvent.of(order, from, to, "sign"));

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

        // PR2: 发布"取消"事件 (任意非终态 → CANCELLED).
        eventBus.publish(DeliveryOrderStateChangedEvent.of(order, from, to, "cancel"));

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

        // PR2: 发布"报异常"事件 (任意非终态 → EXCEPTION).
        eventBus.publish(DeliveryOrderStateChangedEvent.of(order, from, to, "raiseException"));

        // PR2: 同时发布"新建异常工单"事件 (null → OPEN). 该事件由 raiseException 触发,
        // 而非走 DeliveryExceptionServiceImpl.create() — 因为 EX 的 create() 按
        // 状态机契约是初始化而非迁移, 不在本切片 4 个迁移范围. 在这里发布让下游
        // 订阅方拿到"工单新建"信号, 同时配合 raiseException 整体语义.
        eventBus.publish(DeliveryExceptionStateChangedEvent.of(ex, null,
                DispatchConstants.EX_OPEN, "create"));

        log.info("Delivery order {} -> EXCEPTION: exceptionId={} type={}",
                id, ex.getId(), req.getType());
        return ex;
    }

    // ================================================================
    // resolveException (Iteration 4.1.x PR1)
    // ================================================================
    // 异常工单走完 OPEN→INVESTIGATING→RESOLVED→CLOSED 之后, 配送单仍卡在
    // EXCEPTION — 由调度员决定是换车重新派 (RE_DISPATCH) 还是直接关单 (CANCEL).
    // 两条分支走 V23 状态机新灌的两行:
    //   EXCEPTION → DISPATCHED  (reDispatch)        + VEHICLE SM IDLE → DISPATCHED
    //   EXCEPTION → CANCELLED   (exceptionCancel)   + reason 拼到 remark
    // ================================================================

    @Override
    @Transactional
    public WmsDeliveryOrder resolveException(Long id, ResolveExceptionRequest req) {
        if (req == null || req.getStrategy() == null) {
            throw new DispatchException(DispatchErrorCode.INVALID_QUERY_PARAM,
                    "resolveException: strategy is required");
        }

        WmsDeliveryOrder order = get(id);

        // 1) 状态前置: 必须在 EXCEPTION
        if (!DispatchConstants.DO_EXCEPTION.equals(order.getStatus())) {
            throw new IllegalDeliveryOrderStateException(
                    "resolveException: order " + id + " is in " + order.getStatus()
                            + ", not EXCEPTION");
        }

        return switch (req.getStrategy()) {
            case RE_DISPATCH -> reDispatchFromException(id, order, req);
            case CANCEL      -> cancelFromException(id, order, req);
        };
    }

    /**
     * RE_DISPATCH 分支: EXCEPTION → DISPATCHED, 同时车辆 IDLE → DISPATCHED.
     * 复用 assign 的 LoadingValidator + VEHICLE SM 校验逻辑 (与 PR4 派车对称).
     */
    private WmsDeliveryOrder reDispatchFromException(Long id, WmsDeliveryOrder order, ResolveExceptionRequest req) {
        if (req.getVehicleId() == null || req.getDriverId() == null) {
            throw new DispatchException(DispatchErrorCode.INVALID_QUERY_PARAM,
                    "RE_DISPATCH requires vehicleId and driverId");
        }

        // V23 新灌的 EXCEPTION → DISPATCHED 迁移 (action=reDispatch)
        stateMachineEngine.assertTransition(
                DispatchConstants.SM_DISPATCH_ORDER,
                DispatchConstants.DO_EXCEPTION,
                DispatchConstants.DO_DISPATCHED);

        WmsVehicle vehicle = vehicleMapper.selectById(req.getVehicleId());
        if (vehicle == null) throw new VehicleNotFoundException(req.getVehicleId());
        loadingValidator.validate(vehicle, order);

        // 车辆状态机: → DISPATCHED (action=dispatch), 失败时降级为 IllegalDeliveryOrderStateException
        String vehFrom = vehicle.getStatus();
        if (!DispatchConstants.VEH_DISPATCHED.equals(vehFrom)) {
            try {
                stateMachineEngine.assertTransition(
                        DispatchConstants.SM_VEHICLE, vehFrom, DispatchConstants.VEH_DISPATCHED);
            } catch (Exception smEx) {
                log.warn("VEHICLE state machine failed for id={}: {}",
                        vehicle.getId(), smEx.getMessage());
                throw new IllegalDeliveryOrderStateException(
                        "vehicle " + vehicle.getId() + " not dispatchable from " + vehFrom);
            }
            vehicle.setStatus(DispatchConstants.VEH_DISPATCHED);
            vehicleMapper.updateById(vehicle);
        }

        order.setVehicleId(req.getVehicleId());
        order.setDriverId(req.getDriverId());
        order.setStatus(DispatchConstants.DO_DISPATCHED);
        // exception_id 保留作为可追溯的工单引用 — 异常工单已 CLOSED, 不会重新触发 raiseException.
        // 也不通过 setExceptionId(null) 清理: MyBatis-Plus 默认 NOT_NULL 策略会跳过 null 更新,
        // 强行改需要 @TableField(updateStrategy = IGNORED), 改动面超出本 PR 范围.
        order.setRemark((order.getRemark() == null ? "" : order.getRemark() + " | ")
                + "RE_DISPATCHED: " + (req.getReason() == null ? "" : req.getReason()));
        orderMapper.updateById(order);

        // PR2: 发布"重新派车"事件 (EXCEPTION → DISPATCHED, V23 reDispatch).
        eventBus.publish(DeliveryOrderStateChangedEvent.of(order,
                DispatchConstants.DO_EXCEPTION,
                DispatchConstants.DO_DISPATCHED,
                DispatchConstants.DO_REDISPATCH_ACTION));

        log.info("Delivery order {} EXCEPTION -> DISPATCHED (reDispatch): vehicle={} driver={}",
                id, req.getVehicleId(), req.getDriverId());
        return orderMapper.selectById(id);
    }

    /**
     * CANCEL 分支: EXCEPTION → CANCELLED, reason 拼到 remark.
     */
    private WmsDeliveryOrder cancelFromException(Long id, WmsDeliveryOrder order, ResolveExceptionRequest req) {
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new DispatchException(DispatchErrorCode.INVALID_QUERY_PARAM,
                    "CANCEL requires a non-blank reason");
        }

        // V23 新灌的 EXCEPTION → CANCELLED 迁移 (action=exceptionCancel)
        stateMachineEngine.assertTransition(
                DispatchConstants.SM_DISPATCH_ORDER,
                DispatchConstants.DO_EXCEPTION,
                DispatchConstants.DO_CANCELLED);

        order.setStatus(DispatchConstants.DO_CANCELLED);
        order.setRemark((order.getRemark() == null ? "" : order.getRemark() + " | ")
                + "EXCEPTION_CANCELLED: " + req.getReason());
        orderMapper.updateById(order);

        // PR2: 发布"异常关单"事件 (EXCEPTION → CANCELLED, V23 exceptionCancel).
        eventBus.publish(DeliveryOrderStateChangedEvent.of(order,
                DispatchConstants.DO_EXCEPTION,
                DispatchConstants.DO_CANCELLED,
                DispatchConstants.DO_EXCEPTION_CANCEL_ACTION));

        log.info("Delivery order {} EXCEPTION -> CANCELLED (exceptionCancel): reason={}",
                id, req.getReason());
        return orderMapper.selectById(id);
    }

    // ================================================================
    // Customer portal overloads (Iteration 4.5A.3 real impl)
    // ================================================================
    // 4.5A.2 占位 stub. 4.5A.3 替换为真实实现:
    //   - pageForCustomer: 用 wms_delivery_order.customer_id 字段
    //     (V34 migration 加列 + backfill 自 wms_outbound_note.customer_id)
    //     + tenantId 过滤, 走 (tenant_id, customer_id) 索引
    //   - getDetailForCustomer: selectById + customerId 权限校验;
    //     返回 DO 关键字段 + empty timeline (4.5A.3 不拼 timeline, 留给 4.5A.4+)

    @Override
    public IPage<Map<String, Object>> pageForCustomer(Long tenantId, Long customerId, TrackQuery q) {
        // TrackQuery 构造器已规范化 page>=1 / size>=1
        Page<WmsDeliveryOrder> pageReq = new Page<>(q.page(), q.size());
        IPage<WmsDeliveryOrder> p = orderMapper.pageForCustomer(pageReq, tenantId, customerId, q);

        // 转 Map<String,Object> 给 lumen-customer 的 PortalTrackController,
        // 显式列字段避免泄漏 BaseEntity 的内部 audit 字段.
        IPage<Map<String, Object>> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>(p.getRecords().size());
        for (WmsDeliveryOrder d : p.getRecords()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("doId", d.getId());
            row.put("doNo", d.getNo());
            row.put("status", d.getStatus());
            // 注: wms_delivery_order 无 origin/dest address / recipient 列, 仅
            // wms_delivery_stop 表有. 显式置 null 给控制器, 留给 4.5A.4+ JOIN.
            row.put("originAddress", null);
            row.put("destAddress", null);
            row.put("recipientName", null);
            row.put("recipientPhone", null);
            row.put("warehouseId", d.getWarehouseId());
            row.put("plannedDepartAt", d.getPlannedDepartAt());
            row.put("actualDepartAt", d.getActualDepartAt());
            row.put("arrivedAt", d.getArrivedAt());
            row.put("signedAt", d.getDeliveredAt());
            row.put("weight", d.getTotalWeightKg());
            row.put("volume", d.getTotalVolumeM3());
            row.put("stopCount", d.getStopCount());
            row.put("createdAt", d.getCreatedAt());
            // 4.5B.1 新增 (T1.1 V36 migration 加列):
            //   商家选取件窗口 — PENDING_DISPATCH 由 portal createForCustomer 填入,
            //   admin 创建的 DO 为 null.
            row.put("plannedPickupWindowStart", d.getPlannedPickupWindowStart());
            row.put("plannedPickupWindowEnd", d.getPlannedPickupWindowEnd());
            rows.add(row);
        }
        result.setRecords(rows);
        return result;
    }

    @Override
    public Map<String, Object> getDetailForCustomer(Long tenantId, Long customerId, Long doId) {
        WmsDeliveryOrder d = orderMapper.selectById(doId);
        if (d == null
                || !tenantId.equals(d.getTenantId())
                || !customerId.equals(d.getCustomerId())) {
            // 跨租户 / 跨客户访问统一抛 404 (不暴露存在性, 也防 tenant 绕过).
            log.warn("getDetailForCustomer cross-tenant/cross-customer access denied: doId={} requestedTenant={} actualTenant={} requestedCustomer={} actualCustomer={}",
                    doId, tenantId, d == null ? null : d.getTenantId(),
                    customerId, d == null ? null : d.getCustomerId());
            throw new DeliveryOrderNotFoundException(doId);
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("doId", d.getId());
        result.put("doNo", d.getNo());
        result.put("status", d.getStatus());
        // 注: 同 pageForCustomer — origin/dest/recipient 数据在 wms_delivery_stop,
        // 4.5A.4+ 再 JOIN; 显式 null 给控制器做防御性读取.
        result.put("originAddress", null);
        result.put("destAddress", null);
        result.put("recipientName", null);
        result.put("recipientPhone", null);
        result.put("warehouseId", d.getWarehouseId());
        result.put("vehicleId", d.getVehicleId());
        result.put("driverId", d.getDriverId());
        result.put("carrierId", d.getCarrierId());
        result.put("plannedDepartAt", d.getPlannedDepartAt());
        result.put("actualDepartAt", d.getActualDepartAt());
        result.put("arrivedAt", d.getArrivedAt());
        result.put("signedAt", d.getDeliveredAt());
        result.put("weight", d.getTotalWeightKg());
        result.put("volume", d.getTotalVolumeM3());
        result.put("stopCount", d.getStopCount());
        result.put("signRequired", d.getSignRequired());
        result.put("signPhotoUrl", d.getSignPhotoUrl());
        result.put("remark", d.getRemark());
        result.put("createdAt", d.getCreatedAt());
        // 4.5B.1 新增 (T1.1 V36 migration 加列):
        //   商家选取件窗口 — PENDING_DISPATCH 由 portal createForCustomer 填入,
        //   admin 创建的 DO 为 null.
        result.put("plannedPickupWindowStart", d.getPlannedPickupWindowStart());
        result.put("plannedPickupWindowEnd", d.getPlannedPickupWindowEnd());
        // 4.5B.1 新增: 仅 PENDING_DISPATCH 可在 portal 端取消.
        result.put("canCancel", DispatchConstants.DO_PENDING_DISPATCH.equals(d.getStatus()));
        // timeline 留待 4.5A.4+ (事件日志聚合)
        result.put("timeline", java.util.List.of());
        return result;
    }
}
