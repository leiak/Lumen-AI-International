package com.lumen.dispatch.engine;

import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.exception.InsufficientVehicleException;

/**
 * 配载校验器接口.
 *
 * <p>校验车辆 (WmsVehicle) 是否能装下指定配送单 (WmsDeliveryOrder).
 * 不符时抛 {@link InsufficientVehicleException} (HTTP 409 Conflict).
 *
 * <p>校验维度（spec §5.2）：
 * <ul>
 *   <li>总重量不超过车型最大载重</li>
 *   <li>总体积不超过车型最大容积</li>
 *   <li>站点数不超过 {@code dlv.max_stop_per_vehicle} (默认 30)</li>
 * </ul>
 */
public interface LoadingValidator {

    /**
     * 校验车辆是否能装下订单.
     *
     * @param vehicle 候选车辆
     * @param order   待派车订单
     * @throws InsufficientVehicleException 任一校验维度不通过时抛出
     */
    void validate(WmsVehicle vehicle, WmsDeliveryOrder order);
}
