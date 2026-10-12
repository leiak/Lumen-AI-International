package com.lumen.dispatch.engine.impl;

import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.engine.LoadingValidator;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.exception.InsufficientVehicleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认配载校验器 — 按重量 / 体积 / 站点数三维校验.
 *
 * <p>容差处理：DB CHECK 保证 {@code total_weight_kg >= 0} /
 * {@code total_volume_m3 >= 0} / {@code max_load_kg > 0} / {@code max_volume_m3 > 0}，
 * 故只需 {@code >} 比较即可. 站点数取 {@code order.getStopCount()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultLoadingValidator implements LoadingValidator {

    @Override
    public void validate(WmsVehicle vehicle, WmsDeliveryOrder order) {
        if (vehicle == null || order == null) {
            throw new IllegalArgumentException("vehicle/order must not be null");
        }
        if (order.getTotalWeightKg() != null
                && vehicle.getMaxLoadKg() != null
                && order.getTotalWeightKg().compareTo(vehicle.getMaxLoadKg()) > 0) {
            log.warn("loading validate failed: weight {} > vehicle.max {}",
                    order.getTotalWeightKg(), vehicle.getMaxLoadKg());
            throw new InsufficientVehicleException(
                    "over weight: order=" + order.getTotalWeightKg()
                            + " vehicle=" + vehicle.getMaxLoadKg());
        }
        if (order.getTotalVolumeM3() != null
                && vehicle.getMaxVolumeM3() != null
                && order.getTotalVolumeM3().compareTo(vehicle.getMaxVolumeM3()) > 0) {
            log.warn("loading validate failed: volume {} > vehicle.max {}",
                    order.getTotalVolumeM3(), vehicle.getMaxVolumeM3());
            throw new InsufficientVehicleException(
                    "over volume: order=" + order.getTotalVolumeM3()
                            + " vehicle=" + vehicle.getMaxVolumeM3());
        }
        int stopCount = order.getStopCount() != null ? order.getStopCount() : 0;
        if (stopCount > DispatchConstants.DEFAULT_MAX_STOP_PER_VEHICLE) {
            log.warn("loading validate failed: stopCount {} > max {}",
                    stopCount, DispatchConstants.DEFAULT_MAX_STOP_PER_VEHICLE);
            throw new InsufficientVehicleException(
                    "too many stops: order=" + stopCount
                            + " max=" + DispatchConstants.DEFAULT_MAX_STOP_PER_VEHICLE);
        }
    }
}
