package com.lumen.dispatch.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lumen.common.api.PageResult;
import com.lumen.common.dto.TrackQuery;
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
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsDeliveryStop;

import java.util.List;
import java.util.Map;

/**
 * 配送订单服务.
 *
 * <p>状态机（spec §4 / V20 + V23 seed）：
 * <pre>
 *   PENDING_DISPATCH --assign--&gt; DISPATCHED --load--&gt; LOADED --depart--&gt; ENROUTE
 *                  --arrive--&gt; ARRIVED --sign--&gt; SIGNED
 *                  --cancel--&gt; CANCELLED   (PENDING_DISPATCH/DISPATCHED/LOADED/ENROUTE)
 *                  --exception--&gt; EXCEPTION (LOADED/ENROUTE/ARRIVED)
 *   EXCEPTION --reDispatch--&gt; DISPATCHED       (V23, 异常工单处理后重新派车)
 *   EXCEPTION --exceptionCancel--&gt; CANCELLED   (V23, 异常工单处理后直接关单)
 * </pre>
 *
 * <p>{@link #assign(Long, AssignRequest)} 内嵌 {@code LoadingValidator}
 * 校验；{@code assign} 同时把车辆从 IDLE → DISPATCHED (VEHICLE SM action=dispatch).
 *
 * <p>{@link #resolveException(Long, ResolveExceptionRequest)} 是 4.1.x PR1 新增:
 * 异常工单 (ExceptionWorkOrder) 走完调查→解决→关闭 之后，配送单仍卡在 EXCEPTION —
 * 由调度员决定是换车重新派 (RE_DISPATCH) 还是直接关单 (CANCEL).
 */
public interface DeliveryOrderService {

    // ================================================================
    // CRUD
    // ================================================================

    /** 分页查询配送单. */
    PageResult<WmsDeliveryOrder> page(long current, long size, QueryDeliveryOrderRequest query);

    /** 主键查询 (NotFound 时抛 {@code DeliveryOrderNotFoundException}). */
    WmsDeliveryOrder get(Long id);

    /** 列出订单下的站点（按 stop_no 升序）. */
    List<WmsDeliveryStop> listStops(Long orderId);

    /** 新建配送单 (status=PENDING_DISPATCH). */
    WmsDeliveryOrder create(CreateDeliveryOrderRequest req);

    /** 更新可变更字段. */
    WmsDeliveryOrder update(Long id, UpdateDeliveryOrderRequest req);

    // ================================================================
    // State machine (spec §7.1)
    // ================================================================

    /** PENDING_DISPATCH → DISPATCHED（含 LoadingValidator + 车辆 VEHICLE SM dispatch）. */
    WmsDeliveryOrder assign(Long id, AssignRequest req);

    /** DISPATCHED → LOADED. */
    WmsDeliveryOrder load(Long id, LoadRequest req);

    /** LOADED → ENROUTE. */
    WmsDeliveryOrder depart(Long id, DepartRequest req);

    /** ENROUTE → ARRIVED + stop.arrived_at = now. */
    WmsDeliveryOrder arrive(Long id, ArriveRequest req);

    /** ARRIVED → SIGNED + stop.completed_at + order.signPhotoUrl. */
    WmsDeliveryOrder sign(Long id, SignRequest req);

    /** any non-terminal → CANCELLED. */
    WmsDeliveryOrder cancel(Long id, CancelRequest req);

    /** non-terminal → EXCEPTION + 新建 OPEN 异常工单. */
    WmsDeliveryException raiseException(Long id, RaiseExceptionRequest req);

    /**
     * 处理 EXCEPTION 状态的配送单 (Iteration 4.1.x PR1).
     *
     * <p>异常工单走完调查→解决→关闭之后, 配送单仍卡在 EXCEPTION. 此时调度员
     * 决定是:
     * <ul>
     *   <li>{@link ResolveExceptionRequest.ResolveStrategy#RE_DISPATCH} —
     *       换一辆车 (req.vehicleId / driverId) 重新派送,
     *       走 V23 状态机 EXCEPTION → DISPATCHED (action=reDispatch).
     *       顺带把车辆从 IDLE → DISPATCHED (VEHICLE SM action=dispatch).</li>
     *   <li>{@link ResolveExceptionRequest.ResolveStrategy#CANCEL} —
     *       直接关单, 走 V23 状态机 EXCEPTION → CANCELLED (action=exceptionCancel),
     *       把 reason 追加到 remark.</li>
     * </ul>
     *
     * @throws DeliveryOrderNotFoundException         id 不存在
     * @throws IllegalDeliveryOrderStateException     当前状态不是 EXCEPTION
     * @throws com.lumen.dispatch.exception.VehicleNotFoundException
     *         RE_DISPATCH 路径上 req.vehicleId 找不到
     * @throws com.lumen.dispatch.exception.InsufficientVehicleException
     *         RE_DISPATCH 路径上 LoadingValidator 容量不足
     * @throws DispatchException                       strategy 非法 (INVALID_QUERY_PARAM)
     */
    WmsDeliveryOrder resolveException(Long id, ResolveExceptionRequest req);

    // ================================================================
    // Customer portal overloads (Iteration 4.5A.2)
    // ================================================================
    // 4.5A.2 占位, 4.5A.3 PR3 替换为真实实现 (扫 customer_id 字段 + tenant 过滤).
    // 返回 Map<String,Object> 而非 lumen-customer DTO — 避免 lumen-dispatch → lumen-customer
    // 的 Maven 循环依赖; PortalTrackController 自己做 Map → DTO 转换.

    /**
     * 客户门户分页查询 (4.5A.2 占位).
     */
    IPage<Map<String, Object>> pageForCustomer(Long tenantId, Long customerId, TrackQuery q);

    /**
     * 客户门户运单详情 (4.5A.2 占位).
     */
    Map<String, Object> getDetailForCustomer(Long tenantId, Long customerId, Long doId);

    // ================================================================
    // Customer portal: 客户下单重载 (Iteration 4.5B.2 T2.3)
    // ================================================================

    /**
     * 客户门户下单 (4.5B.2 T2.3 新增).
     *
     * <p>入口契约:
     * <ul>
     *   <li>强制 tenantId / customerId 从 arg 注入 — 不读 DTO, 防越权.</li>
     *   <li>建 DO (status=PENDING_DISPATCH, sourceType=PORTAL, no=PO- + 8 hex).</li>
     *   <li>同步调 {@code BillingEngineService.estimateAndAccrue}, 失败不阻塞 —
     *       DO 创建是关键路径, finance 失败由后台 job 重试 (4.5B.1 已记录失败取证).</li>
     *   <li>走 {@link com.lumen.extension.outbox.EventBus#publish} 发
     *       {@code DeliveryOrderStateChangedEvent} (from=null, action=create).</li>
     * </ul>
     *
     * @throws com.lumen.dispatch.exception.PortalBadRequestException
     *         pickup window 校验失败 (400_002 / 400_001)
     */
    WmsDeliveryOrder createForCustomer(Long tenantId, Long customerId,
                                       PortalOrderRequest req);

    // ================================================================
    // Customer portal: 客户取消重载 (Iteration 4.5B.2 T2.4)
    // ================================================================

    /**
     * 客户门户取消 (4.5B.2 T2.4 新增).
     *
     * <p>uniform 404 — 跨租户 / 跨客户 / 不存在 都抛 {@code DeliveryOrderNotFoundException},
     * 避免泄漏存在性, 也防越权.
     *
     * <p>仅 {@link DispatchConstants#DO_PENDING_DISPATCH} 可取消; 其它状态抛
     * {@code PortalBadRequestException} (code=409_001 ORDER_NOT_CANCELLABLE) —
     * 由 lumen-customer 的 controller 重新包装为 {@code CustomerException} 走 HTTP 映射.
     *
     * <p>改 status + remark 后缀 (保留原 remark, 追加 cancel 标注), 并发
     * {@code DeliveryOrderStateChangedEvent} (action=cancel) 走 outbox.
     *
     * <p>4.5C-1 — 计费冲销 (与 {@code createForCustomer} 写入的 OPEN accrual 对称):
     * <ul>
     *   <li>改 status <b>之前</b>调 {@code billingEngineService.assertReversible} 预检 —
     *       已开票 (CONVERTED/LOCKED) 时整个取消被拒, 此时 DO 一个字节都没改.</li>
     *   <li>改 status <b>之后</b>调 {@code billingEngineService.reversalForSourceEventId}
     *       释放应计, 失败只记 ERROR 日志, <b>不抛出、不阻塞取消</b>.</li>
     * </ul>
     *
     * @throws com.lumen.dispatch.exception.DeliveryOrderNotFoundException
     *         跨租户 / 跨客户 / 不存在 (uniform 404)
     * @throws com.lumen.dispatch.exception.PortalBadRequestException
     *         当前状态不是 PENDING_DISPATCH, 或应计已完成计费 — 都是 409_001
     *         (<b>计费冲销本身的失败不会抛异常</b>, 只落 ERROR 日志)
     */
    WmsDeliveryOrder cancelForCustomer(Long tenantId, Long customerId, Long doId, String reason);
}
