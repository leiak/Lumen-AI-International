package com.lumen.dispatch.service;

import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.entity.WmsDeliveryOrder;

/**
 * 调度编排引擎 — 业务级复合操作 (cross-service orchestration).
 *
 * <p>PR4 暴露两个能力：
 * <ul>
 *   <li>{@link #autoAssign(Long)} — 给定配送单自动选车派车，触发 LoadingValidator + 派车 SM</li>
 *   <li>{@link #disposeException(Long)} — 关闭异常工单（不影响配送单 — 后者单独走 cancel / exception 流）</li>
 * </ul>
 */
public interface DispatchEngine {

    /**
     * 自动派车 — 查询同租户空闲且容量满足的车辆，按 ID 升序选第一个，
     * 调用 {@code LoadingValidator.validate()}，再委托 {@code DeliveryOrderService.assign}.
     *
     * <p>无可用车辆时抛 {@code InsufficientVehicleException}.
     */
    WmsDeliveryOrder autoAssign(Long orderId);

    /**
     * 关闭异常工单（包装 {@code DeliveryExceptionService.close}）.
     */
    WmsDeliveryException disposeException(Long exceptionId);
}
