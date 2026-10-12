package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.DeliveryOrderDtos.AssignRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.CreateDeliveryOrderRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.LoadRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.SignRequest;
import com.lumen.dispatch.dto.DriverDtos;
import com.lumen.dispatch.dto.UtilizationDtos.DailyUtilizationRow;
import com.lumen.dispatch.dto.UtilizationDtos.FleetUtilizationResponse;
import com.lumen.dispatch.dto.VehicleDtos;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsDeliveryStop;
import com.lumen.dispatch.entity.WmsDriver;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.exception.DispatchException;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.mapper.WmsDeliveryStopMapper;
import com.lumen.dispatch.query.VehicleUtilizationQuery;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.dispatch.service.DriverService;
import com.lumen.dispatch.service.VehicleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VehicleUtilizationQuery 集成测试 (Iteration 4.1 PR5).
 *
 * <p>覆盖 4 个场景：
 * <ul>
 *   <li>无数据时 daily + fleet 都返 0 / 空 list</li>
 *   <li>单日单车走完整 sign 流程后能查到一行</li>
 *   <li>多日多车：fleet 总数累加</li>
 *   <li>per-vehicle 过滤生效：只返回目标车辆行</li>
 * </ul>
 */
@DisplayName("VehicleUtilizationQuery 集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DispatchUtilizationIT extends IntegrationBase {

    private static final AtomicLong SEQ = new AtomicLong(0);

    @Autowired private VehicleUtilizationQuery utilizationQuery;
    @Autowired private DeliveryOrderService deliveryOrderService;
    @Autowired private VehicleService vehicleService;
    @Autowired private DriverService driverService;
    @Autowired private WmsDeliveryStopMapper stopMapper;
    @Autowired private WmsDeliveryOrderMapper orderMapper;

    private WmsVehicle vehicle;
    private WmsDriver driver;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);

        DriverDtos.CreateDriverRequest dReq = newDriver();
        driver = driverService.create(dReq);

        VehicleDtos.CreateVehicleRequest vReq = newVehicle();
        vReq.setDriverId(driver.getId());
        vehicle = vehicleService.create(vReq);
    }

    @AfterEach
    void tearDown() {
        try { if (vehicle != null) vehicleService.delete(vehicle.getId()); } catch (Exception ignored) {}
        try { if (driver != null) driverService.delete(driver.getId()); } catch (Exception ignored) {}
        TenantContext.clear();
    }

    @Test
    @Order(1)
    @DisplayName("utilization_emptyData_returnsZeroCounts")
    void emptyData_returnsZeroCounts() {
        // 选一个不会有数据的远期时间窗 (2030)
        LocalDate farStart = LocalDate.of(2030, 1, 1);
        LocalDate farEnd = LocalDate.of(2030, 1, 3);

        List<DailyUtilizationRow> daily = utilizationQuery.dailyUtilization(farStart, farEnd, null);
        assertThat(daily).isEmpty();

        FleetUtilizationResponse fleet = utilizationQuery.fleetUtilization(farStart, farEnd);
        assertThat(fleet.getTotalDispatched()).isEqualTo(0L);
        assertThat(fleet.getTotalWeightKg()).isEqualByComparingTo("0");
        assertThat(fleet.getTotalVolumeM3()).isEqualByComparingTo("0");
        assertThat(fleet.getAverageUtilizationRate()).isEqualByComparingTo("0.0000");
    }

    @Test
    @Order(2)
    @DisplayName("utilization_singleDay_singleVehicle_returnsUtilization")
    void singleDay_singleVehicle_returnsUtilization() {
        LocalDate today = LocalDate.now();
        // 走完整 sign 流程让 order 进入 SIGNED + actual_depart_at
        WmsDeliveryOrder order = createAndSignOrder(new BigDecimal("100"), new BigDecimal("1"));

        // 强行把 actual_depart_at 改成 today（service.depart 用 now()，但 IT 跑得慢
        // 可能跨日；显式 update 保证 deterministic）
        forceDepartAt(order.getId(), today.atTime(8, 0));

        List<DailyUtilizationRow> rows = utilizationQuery.dailyUtilization(today, today, null);
        assertThat(rows).isNotEmpty();
        DailyUtilizationRow mine = rows.stream()
                .filter(r -> r.getVehicleId().equals(vehicle.getId()))
                .findFirst()
                .orElse(null);
        assertThat(mine).isNotNull();
        assertThat(mine.getDispatchedCount()).isGreaterThanOrEqualTo(1L);
        assertThat(mine.getTotalWeightKg()).isGreaterThanOrEqualTo(new BigDecimal("100"));
        assertThat(mine.getUtilizationRate()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    }

    @Test
    @Order(3)
    @DisplayName("utilization_multiDay_aggregatesCorrectly")
    void multiDay_aggregatesCorrectly() {
        LocalDate today = LocalDate.now();

        // day1
        WmsDeliveryOrder o1 = createAndSignOrder(new BigDecimal("100"), new BigDecimal("1"));
        forceDepartAt(o1.getId(), today.minusDays(1).atTime(8, 0));

        // day2
        WmsDeliveryOrder o2 = createAndSignOrder(new BigDecimal("200"), new BigDecimal("2"));
        forceDepartAt(o2.getId(), today.atTime(8, 0));

        FleetUtilizationResponse resp = utilizationQuery.fleetUtilization(
                today.minusDays(1), today);

        assertThat(resp.getTotalDispatched()).isGreaterThanOrEqualTo(2L);
        assertThat(resp.getTotalWeightKg()).isGreaterThanOrEqualTo(new BigDecimal("300"));
        assertThat(resp.getDailyBreakdown()).isNotEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("utilization_perVehicleFilter_narrowsResult")
    void perVehicleFilter_narrowsResult() {
        LocalDate today = LocalDate.now();
        WmsDeliveryOrder order = createAndSignOrder(new BigDecimal("100"), new BigDecimal("1"));
        forceDepartAt(order.getId(), today.atTime(8, 0));

        // 创建第二辆车 + 司机，模拟"另一辆车也有数据"（不签单，单纯让 vehicleById 有它）
        WmsDriver otherDriver = driverService.create(newDriver());
        VehicleDtos.CreateVehicleRequest otherVReq = newVehicle();
        otherVReq.setDriverId(otherDriver.getId());
        otherVReq.setPlateNo("OTH-" + unique());
        WmsVehicle otherVehicle = vehicleService.create(otherVReq);
        try {
            List<DailyUtilizationRow> rows = utilizationQuery.dailyUtilization(
                    today, today, vehicle.getId());
            // 过滤后所有行的 vehicleId 都应等于本车的 id
            assertThat(rows).allMatch(r -> r.getVehicleId().equals(vehicle.getId()));
        } finally {
            try { vehicleService.delete(otherVehicle.getId()); } catch (Exception ignored) {}
            try { driverService.delete(otherDriver.getId()); } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(5)
    @DisplayName("utilization_invalidRange_throws")
    void invalidRange_throws() {
        assertThatThrownBy(() -> utilizationQuery.dailyUtilization(
                LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 1), null))
                .isInstanceOf(DispatchException.class)
                .hasMessageContaining("dateEnd");
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private WmsDeliveryOrder createAndSignOrder(BigDecimal weight, BigDecimal volume) {
        CreateDeliveryOrderRequest req = new CreateDeliveryOrderRequest();
        req.setSourceNo("OB-IT-UTIL-" + System.nanoTime());
        req.setWarehouseId(1L);
        req.setTotalWeightKg(weight);
        req.setTotalVolumeM3(volume);
        req.setStopCount(1);

        WmsDeliveryOrder order = deliveryOrderService.create(req);

        AssignRequest assignReq = new AssignRequest();
        assignReq.setVehicleId(vehicle.getId());
        assignReq.setDriverId(driver.getId());
        deliveryOrderService.assign(order.getId(), assignReq);
        deliveryOrderService.load(order.getId(), new LoadRequest());
        deliveryOrderService.depart(order.getId(),
                new com.lumen.dispatch.dto.DeliveryOrderDtos.DepartRequest());

        WmsDeliveryStop stop = new WmsDeliveryStop();
        stop.setTenantId(1L);
        stop.setOrderId(order.getId());
        stop.setStopNo(1);
        stop.setAddress("客户地址 (UTIL IT)");
        stop.setContactName("客户");
        stop.setContactPhone("13800000000");
        stop.setStatus("PENDING");
        stopMapper.insert(stop);

        // V20 状态机要求 ENROUTE → ARRIVED → SIGNED，所以必须先 arrive
        com.lumen.dispatch.dto.DeliveryOrderDtos.ArriveRequest arriveReq =
                new com.lumen.dispatch.dto.DeliveryOrderDtos.ArriveRequest();
        arriveReq.setStopId(stop.getId());
        deliveryOrderService.arrive(order.getId(), arriveReq);

        SignRequest signReq = new SignRequest();
        signReq.setStopId(stop.getId());
        signReq.setSignPhotoUrl("https://example.com/util-it-" + System.nanoTime() + ".jpg");
        return deliveryOrderService.sign(order.getId(), signReq);
    }

    private void forceDepartAt(Long orderId, LocalDateTime when) {
        UpdateWrapper<WmsDeliveryOrder> uw = new UpdateWrapper<>();
        uw.eq("id", orderId).set("actual_depart_at", when);
        orderMapper.update(null, uw);
    }

    private static DriverDtos.CreateDriverRequest newDriver() {
        DriverDtos.CreateDriverRequest d = new DriverDtos.CreateDriverRequest();
        d.setName("Util-Drv-" + unique().substring(0, 6));
        d.setPhone("13800" + System.nanoTime() % 1000000L);
        d.setLicenseNo("LIC-" + unique().substring(0, 8));
        return d;
    }

    private static VehicleDtos.CreateVehicleRequest newVehicle() {
        VehicleDtos.CreateVehicleRequest v = new VehicleDtos.CreateVehicleRequest();
        v.setPlateNo("UTL-" + unique());
        v.setType("MEDIUM");
        v.setMaxLoadKg(new BigDecimal("500.00"));
        v.setMaxVolumeM3(new BigDecimal("5.00"));
        return v;
    }

    private static String unique() {
        long nanos = System.nanoTime();
        String suffix = Long.toString(nanos);
        if (suffix.length() > 9) suffix = suffix.substring(suffix.length() - 9);
        return suffix + SEQ.incrementAndGet();
    }
}
