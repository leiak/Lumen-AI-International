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
import com.lumen.dispatch.dto.DeliveryOrderDtos.SignRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.UpdateDeliveryOrderRequest;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsDeliveryStop;

import java.util.List;

/**
 * 配送订单服务.
 *
 * <p>状态机（spec §4 / V20 seed）：
 * <pre>
 *   PENDING_DISPATCH --assign--&gt; DISPATCHED --load--&gt; LOADED --depart--&gt; ENROUTE
 *                  --arrive--&gt; ARRIVED --sign--&gt; SIGNED
 *                  --cancel--&gt; CANCELLED   (PENDING_DISPATCH/DISPATCHED/LOADED/ENROUTE)
 *                  --exception--&gt; EXCEPTION (LOADED/ENROUTE/ARRIVED)
 * </pre>
 *
 * <p>{@link #assign(Long, AssignRequest)} 内嵌 {@code LoadingValidator}
 * 校验；{@code assign} 同时把车辆从 IDLE → DISPATCHED (VEHICLE SM action=dispatch).
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
}
