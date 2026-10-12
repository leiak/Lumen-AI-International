package com.lumen.dispatch.engine;

import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.engine.impl.DefaultLoadingValidator;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.exception.InsufficientVehicleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DefaultLoadingValidator 单元测试 (Iteration 4.1 PR4).
 *
 * <p>覆盖三维校验：重量 / 体积 / 站点数。
 */
@DisplayName("DefaultLoadingValidator 单元测试")
class DefaultLoadingValidatorTest {

    private DefaultLoadingValidator validator;
    private WmsVehicle vehicle;
    private WmsDeliveryOrder order;

    @BeforeEach
    void setUp() {
        validator = new DefaultLoadingValidator();
        vehicle = new WmsVehicle();
        vehicle.setId(1L);
        vehicle.setMaxLoadKg(new BigDecimal("500"));
        vehicle.setMaxVolumeM3(new BigDecimal("5"));

        order = new WmsDeliveryOrder();
        order.setId(100L);
        order.setTotalWeightKg(new BigDecimal("100"));
        order.setTotalVolumeM3(new BigDecimal("1"));
        order.setStopCount(5);
    }

    @Test
    @DisplayName("pass: 重量/体积/站点数均在范围内")
    void pass_withinRange() {
        assertThatCode(() -> validator.validate(vehicle, order)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("over weight: 订单重量 > 车辆载重 → InsufficientVehicleException")
    void overWeight() {
        order.setTotalWeightKg(new BigDecimal("600"));
        assertThatThrownBy(() -> validator.validate(vehicle, order))
                .isInstanceOf(InsufficientVehicleException.class)
                .hasMessageContaining("over weight");
    }

    @Test
    @DisplayName("over volume: 订单体积 > 车辆容积 → InsufficientVehicleException")
    void overVolume() {
        order.setTotalVolumeM3(new BigDecimal("6"));
        assertThatThrownBy(() -> validator.validate(vehicle, order))
                .isInstanceOf(InsufficientVehicleException.class)
                .hasMessageContaining("over volume");
    }

    @Test
    @DisplayName("over stop count: 订单站点数 > 默认上限 30 → InsufficientVehicleException")
    void overStopCount() {
        order.setStopCount(DispatchConstants.DEFAULT_MAX_STOP_PER_VEHICLE + 1);
        assertThatThrownBy(() -> validator.validate(vehicle, order))
                .isInstanceOf(InsufficientVehicleException.class)
                .hasMessageContaining("too many stops");
    }
}
