package com.lumen.dispatch.service;

import com.lumen.common.api.PageResult;
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
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsDeliveryStop;

import java.util.List;

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
}
