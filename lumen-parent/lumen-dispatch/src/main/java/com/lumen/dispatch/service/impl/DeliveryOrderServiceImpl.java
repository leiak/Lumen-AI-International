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
import com.lumen.dispatch.dto.PortalOrderRequest;
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
import com.lumen.dispatch.exception.PortalBadRequestException;
import com.lumen.dispatch.exception.VehicleNotFoundException;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.mapper.WmsDeliveryStopMapper;
import com.lumen.dispatch.mapper.WmsVehicleMapper;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.extension.outbox.EventBus;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingEventDto;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.service.BillingEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 配送订单服务实现 (Iteration 4.1 PR4 + 4.1.x PR1 + 4.5B.2 T2.3).
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
 *
 * <p>{@code createForCustomer} (4.5B.2 T2.3) 客户门户下单: 同步调用
 * {@link BillingEngineService#estimateAndAccrue(BillingEventDto)} —
 * 失败不阻塞 DO 创建 (estimateAndAccrue 内部已写 OPEN_FAILED accrual).
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
    /**
     * 4.5B.2 T2.3 新增 — 客户门户下单时同步 estimateAndAccrue.
     * 失败不阻塞 DO 创建 (finance 失败由后台 outbox 重试 + 已写 OPEN_FAILED 取证).
     */
    private final BillingEngineService billingEngineService;

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

    // ================================================================
    // createForCustomer (Iteration 4.5B.2 T2.3 — 客户门户下单)
    // ================================================================
    // 契约:
    //   1. tenantId / customerId 强制从 arg 注入 — 不读 DTO, 防越权 (4.5A.3 同款)
    //   2. validatePickupWindow — start >= now+2h, <= now+30d, 8h 内同日, end > start
    //   3. 建 DO (status=PENDING_DISPATCH, sourceType=PORTAL, no=PO- + 8 hex)
    //   4. 同步调 estimateAndAccrue, 失败不阻塞
    //   5. 发 DeliveryOrderStateChangedEvent (from=null, action=create) 走 outbox
    // ================================================================

    @Override
    @Transactional
    public WmsDeliveryOrder createForCustomer(Long tenantId, Long customerId, PortalOrderRequest req) {
        // 1. 参数前置: tenantId / customerId 不能从 req 读 (强制 arg 注入)
        if (tenantId == null || customerId == null) {
            throw new DispatchException(DispatchErrorCode.INVALID_QUERY_PARAM,
                    "createForCustomer: tenantId / customerId are required");
        }

        // 2. 取件窗口校验
        validatePickupWindow(req);

        // 3. 构造 DO
        WmsDeliveryOrder order = new WmsDeliveryOrder();
        order.setTenantId(tenantId);
        order.setCustomerId(customerId);
        order.setNo("PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setSourceType("PORTAL");
        order.setStatus(DispatchConstants.DO_PENDING_DISPATCH);
        order.setSignRequired(1);
        order.setWarehouseId(req.warehouseId());
        // 来源单号占位填 shipperAddress — wms_delivery_order 表当前无 origin 列
        // (4.5A.3 调研确认), 4.5B 后续 PR 把地址写进 wms_delivery_stop.
        order.setSourceNo(req.shipperAddress());
        order.setTotalWeightKg(req.totalWeightKg());
        order.setTotalVolumeM3(req.totalVolumeM3());
        order.setStopCount(req.stopCount());
        order.setPlannedPickupWindowStart(req.plannedPickupWindowStart());
        // 终点默认 = 起点 + 4h — service 兜底 (DTO 允许 null)
        LocalDateTime windowEnd = req.plannedPickupWindowEnd() != null
                ? req.plannedPickupWindowEnd()
                : req.plannedPickupWindowStart().plusHours(4);
        order.setPlannedPickupWindowEnd(windowEnd);
        // consigneeAddress 拼到 remark (当前无独立列, 4.5B 后续 PR 把地址写进 wms_delivery_stop)
        order.setRemark(mergeRemark(req.remark(), "收件地址: " + req.consigneeAddress()));
        orderMapper.insert(order);

        log.info("createForCustomer: DO created id={} no={} tenantId={} customerId={} warehouseId={}",
                order.getId(), order.getNo(), tenantId, customerId, req.warehouseId());

        // 4. 同步 estimateAndAccrue — 失败不阻塞 (estimateAndAccrue 内部已写 OPEN_FAILED accrual)
        try {
            BillingEventDto event = new BillingEventDto(
                    tenantId,
                    String.valueOf(order.getId()),    // sourceEventId = doId (幂等键)
                    "LAST_MILE",                       // businessType
                    order.getId(),                     // businessId
                    "RECEIVABLE",                      // direction (应收)
                    customerId,                        // counterpartyId
                    "CUSTOMER",                        // counterpartyType
                    Map.of(
                            // BillingScriptEngine 沙箱白名单 (9 个变量) 的子集 — key 超出白名单
                            // 会抛 FinanceException_UnknownVar, 导致 accrue 永远失败.
                            "weight", req.totalWeightKg(),
                            "quantity", req.stopCount(),
                            "distance", BigDecimal.ZERO  // 4.5B 暂不接地图 API
                    )
            );
            billingEngineService.estimateAndAccrue(event);
        } catch (Exception ex) {
            log.warn("createForCustomer: estimateAndAccrue failed for doId={}, reason={}",
                    order.getId(), ex.getMessage());
        }

        // 5. 发事件 (via eventBus, 走 outbox)
        eventBus.publish(new DeliveryOrderStateChangedEvent(
                order.getTenantId(),
                order.getId(),
                order.getNo(),
                null,
                DispatchConstants.DO_PENDING_DISPATCH,
                "create",
                order.getVehicleId(),
                order.getDriverId(),
                order.getTotalWeightKg(),
                order.getTotalVolumeM3(),
                order.getStopCount(),
                order.getExceptionId(),
                resolveActorForPortal()));

        return order;
    }

    /**
     * 校验取件时间窗口 (4.5B.2 T2.3):
     * <ul>
     *   <li>start &gt;= now + 2h (太近不让下) </li>
     *   <li>start &lt;= now + 30d (太远不让下)</li>
     *   <li>end &gt; start</li>
     *   <li>end &lt;= start + 8h (单窗口最多 8 小时)</li>
     *   <li>start / end 同日 (日内窗口)</li>
     * </ul>
     *
     * <p>{@code PortalBadRequestException} 抛出后由 lumen-customer 的 controller
     * 捕获并重新包装为 {@code CustomerException} 走 HTTP 映射 (4.5B.2 T2.6 实现).
     */
    private void validatePickupWindow(PortalOrderRequest req) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = req.plannedPickupWindowStart();
        // 终点空 → 默认 start+4h
        LocalDateTime end = req.plannedPickupWindowEnd() != null
                ? req.plannedPickupWindowEnd()
                : start.plusHours(4);

        if (start.isBefore(now.plusHours(2)) || start.isAfter(now.plusDays(30))) {
            throw new PortalBadRequestException(400_002,
                    "取件时间窗口起点需 >= 当前 + 2 小时, <= 当前 + 30 天");
        }
        if (!end.isAfter(start) || end.isAfter(start.plusHours(8))) {
            throw new PortalBadRequestException(400_001,
                    "取件时间窗口终点需 > 起点, <= 起点 + 8 小时");
        }
        if (!end.toLocalDate().equals(start.toLocalDate())) {
            throw new PortalBadRequestException(400_001,
                    "取件时间窗口需在同一天");
        }
    }

    /**
     * mergeRemark: 把额外文本追加到原 remark (空 → 直接取 extra).
     */
    private String mergeRemark(String base, String extra) {
        if (base == null || base.isBlank()) return extra;
        if (extra == null || extra.isBlank()) return base;
        return base + " | " + extra;
    }

    /**
     * Portal 端没有 Spring Security 上下文 — 直接从 TenantContext / DTO 强拿?
     * 4.5A.4+ 引入 portal 自有登录态后, 此处替换成 customer 用户 id.
     */
    private static String resolveActorForPortal() {
        return "portal";
    }

    // ================================================================
    // cancelForCustomer (Iteration 4.5B.2 T2.4 — 客户门户取消)
    // ================================================================
    // 契约:
    //   1. tenantId / customerId / doId 强制从 arg 注入 — 不读 DTO, 防越权
    //   2. uniform 404: 跨租户 / 跨客户 / 不存在 都抛 DeliveryOrderNotFoundException
    //      (不暴露存在性, 也防 tenant 绕过)
    //   3. 状态 guard: 仅 PENDING_DISPATCH 可取消, 其它抛 PortalBadRequestException(409_001)
    //   4. 改 status=CANCELLED + remark 后缀 (保留原 remark, 追加 cancel 标注)
    //   5. 发 DeliveryOrderStateChangedEvent (from=PENDING_DISPATCH, to=CANCELLED,
    //      action=cancel, actor=portal) 走 outbox
    //   6. 4.5C-1: 改状态之前 调 billingEngineService.assertReversible 预检
    //      (已开票 → 409_001, DO 不被改); 改状态之后 调
    //      reversalForSourceEventId 冲销 (失败只记 ERROR, 不阻塞取消)
    //
    //   时序不可换 (4.5C-1):
    //     预检在改状态之前 —— 反了会留下 "DO 已 CANCELLED 但账上仍有应计" 的半成品单;
    //     冲销在改状态之后 —— 反了会留下永久漏计费的单 (少计费可补, 虚增收入不可逆).
    // ================================================================

    @Override
    @Transactional
    public WmsDeliveryOrder cancelForCustomer(Long tenantId, Long customerId, Long doId, String reason) {
        WmsDeliveryOrder order = orderMapper.selectById(doId);
        // uniform 404: 跨租户 / 跨客户 / 不存在 都按"不存在"处理, 避免泄漏存在性.
        if (order == null
                || !Objects.equals(order.getTenantId(), tenantId)
                || !Objects.equals(order.getCustomerId(), customerId)) {
            log.warn("cancelForCustomer cross-tenant/cross-customer/not-found denied: doId={} requestedTenant={} requestedCustomer={}",
                    doId, tenantId, customerId);
            throw new DeliveryOrderNotFoundException(doId);
        }
        // 状态 guard: 仅 PENDING_DISPATCH 可取消, 其它抛 PortalBadRequestException(409_001).
        if (!DispatchConstants.DO_PENDING_DISPATCH.equals(order.getStatus())) {
            throw new PortalBadRequestException(409_001,
                    "订单当前状态不可取消 (仅 PENDING_DISPATCH 可取消), 当前状态: " + order.getStatus());
        }

        // 4.5C-1 — 计费预检 (纯读). 必须放在改 DO 状态之前:
        //    这单若已开票, 取消要整个拒掉; 若先改了状态再发现, 就留下一张
        //    "DO 已 CANCELLED 但账上仍有应计" 的半成品单。
        //
        //    catch 放大到 Exception: assertReversible 内部裸调 accrualMapper.selectOne,
        //    DB 抖动抛出来的是 Spring DataAccessException (RuntimeException, 不是 FinanceException);
        //    业务前置条件只有「已开票 (ACCRUAL_ALREADY_CONVERTED)」一条需硬拒, 其余
        //    异常一律放行 —— 让下面的 reversal 在 REQUIRES_NEW 隔离里自行决定成败。
        try {
            billingEngineService.assertReversible(tenantId, String.valueOf(order.getId()));
        } catch (Exception ex) {
            if (ex instanceof FinanceException fe
                    && FinanceErrorCode.ACCRUAL_ALREADY_CONVERTED == fe.getErrorCode()) {
                throw new PortalBadRequestException(409_001, "订单已完成计费，无法取消");
            }
            log.warn("cancelForCustomer: assertReversible 异常 (不阻断) doId={} 原因={}",
                    order.getId(), ex.toString());
        }

        // 改 status + remark 后缀 (保留原 remark).
        String oldRemark = order.getRemark() == null ? "" : order.getRemark();
        String cancelTag = "\n[cancel by customer " + customerId + ": " + (reason == null ? "" : reason) + "]";
        order.setRemark(oldRemark + cancelTag);
        order.setStatus(DispatchConstants.DO_CANCELLED);
        orderMapper.updateById(order);

        // 4.5C-1 — 计费冲销 (写). 放在改状态之后: 万一失败, 留下的失败方向是
        //    "DO 已取消、应计未释放 + ERROR 日志" —— 可观测可补, 且偏向漏计费
        //    而不是虚增收入。反过来 (先冲销) 会留下永久漏计费的单。
        //
        //    非阻塞: 冲销失败只记 ERROR 不 rethrow。它在 finance 侧的 REQUIRES_NEW
        //    事务里跑, 失败只回滚自己, 不会把本方法的事务标 rollback-only。
        try {
            billingEngineService.reversalForSourceEventId(
                    tenantId, String.valueOf(order.getId()),
                    reason == null ? "portal cancel" : reason);
        } catch (Exception ex) {
            log.error("cancelForCustomer: 计费冲销失败, 订单已取消但应计未释放 "
                    + "tenantId={} doId={} sourceEventId={} businessType={} 原因={}",
                    tenantId, order.getId(), String.valueOf(order.getId()),
                    FinanceConstants.BIZ_LAST_MILE, ex.toString(), ex);
        }

        // 发事件 (via eventBus, 走 outbox; actor=portal 与 createForCustomer 对称).
        eventBus.publish(new DeliveryOrderStateChangedEvent(
                order.getTenantId(),
                order.getId(),
                order.getNo(),
                DispatchConstants.DO_PENDING_DISPATCH,
                DispatchConstants.DO_CANCELLED,
                "cancel",
                order.getVehicleId(),
                order.getDriverId(),
                order.getTotalWeightKg(),
                order.getTotalVolumeM3(),
                order.getStopCount(),
                order.getExceptionId(),
                resolveActorForPortal()));

        log.info("cancelForCustomer: DO cancelled id={} no={} tenantId={} customerId={} reason={}",
                order.getId(), order.getNo(), tenantId, customerId, reason);
        return order;
    }
}
