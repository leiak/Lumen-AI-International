package com.lumen.dispatch.query;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.dto.UtilizationDtos.DailyUtilizationRow;
import com.lumen.dispatch.dto.UtilizationDtos.FleetUtilizationResponse;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.exception.DispatchException;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.mapper.WmsVehicleMapper;
import com.lumen.dispatch.query.impl.VehicleUtilizationQueryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * VehicleUtilizationQuery 单元测试 (Iteration 4.1 PR5).
 *
 * <p>覆盖 4 个场景：
 * <ul>
 *   <li>无数据时返回空 list / zero totals</li>
 *   <li>单日单车的 utilization rate 算到小数 4 位</li>
 *   <li>多日多车：fleet 端点的 totals 累加正确</li>
 *   <li>per-vehicle 过滤生效：只返回该车行</li>
 * </ul>
 *
 * <p>使用 {@code Strictness.LENIENT} — 大量 mock 但每个 case 只用子集.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VehicleUtilizationQuery 单元测试")
class VehicleUtilizationQueryTest {

    @Mock private WmsDeliveryOrderMapper orderMapper;
    @Mock private WmsVehicleMapper vehicleMapper;

    @InjectMocks private VehicleUtilizationQueryImpl service;

    private WmsVehicle vehicle;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);

        vehicle = new WmsVehicle();
        vehicle.setId(200L);
        vehicle.setPlateNo("京A00001");
        vehicle.setMaxLoadKg(new BigDecimal("500"));
        vehicle.setMaxVolumeM3(new BigDecimal("5"));
        vehicle.setStatus("IDLE");
        lenient().when(vehicleMapper.selectBatchIds(any())).thenReturn(List.of(vehicle));
        lenient().when(vehicleMapper.selectById(200L)).thenReturn(vehicle);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("dailyUtilization_emptyData_returnsEmptyList")
    void emptyData_returnsEmptyList() {
        when(orderMapper.aggregateByDateInTenant(any(), any(), anyLong()))
                .thenReturn(Collections.emptyList());

        List<DailyUtilizationRow> rows = service.dailyUtilization(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), null);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("dailyUtilization_singleDay_singleVehicle_returnsUtilization")
    void singleDay_singleVehicle_returnsUtilization() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("date", Date.valueOf("2026-10-05"));
        raw.put("vehicle_id", 200L);
        raw.put("dispatched_count", 2);
        raw.put("total_weight_kg", new BigDecimal("150.00"));
        raw.put("total_volume_m3", new BigDecimal("1.50"));

        when(orderMapper.aggregateByDateInTenant(any(), any(), anyLong()))
                .thenReturn(List.of(raw));

        List<DailyUtilizationRow> rows = service.dailyUtilization(
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5), null);

        assertThat(rows).hasSize(1);
        DailyUtilizationRow r = rows.get(0);
        assertThat(r.getDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(r.getVehicleId()).isEqualTo(200L);
        assertThat(r.getPlateNo()).isEqualTo("京A00001");
        assertThat(r.getDispatchedCount()).isEqualTo(2L);
        // 150 / (500 * 3) = 0.1000
        assertThat(r.getUtilizationRate()).isEqualByComparingTo("0.1000");
    }

    @Test
    @DisplayName("fleetUtilization_multiDay_aggregatesCorrectly")
    void multiDay_aggregatesCorrectly() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("date", Date.valueOf("2026-10-01"));
        r1.put("vehicle_id", 200L);
        r1.put("dispatched_count", 3);
        r1.put("total_weight_kg", new BigDecimal("300.00"));
        r1.put("total_volume_m3", new BigDecimal("6.00"));

        Map<String, Object> r2 = new HashMap<>();
        r2.put("date", Date.valueOf("2026-10-02"));
        r2.put("vehicle_id", 200L);
        r2.put("dispatched_count", 5);
        r2.put("total_weight_kg", new BigDecimal("750.00"));
        r2.put("total_volume_m3", new BigDecimal("9.00"));

        when(orderMapper.aggregateByDateInTenant(any(), any(), anyLong()))
                .thenReturn(List.of(r1, r2));

        FleetUtilizationResponse resp = service.fleetUtilization(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));

        assertThat(resp.getTotalDispatched()).isEqualTo(8L);
        assertThat(resp.getTotalWeightKg()).isEqualByComparingTo("1050.00");
        assertThat(resp.getTotalVolumeM3()).isEqualByComparingTo("15.00");
        // avg = (300+750) / (500*3 * 2 days) = 1050 / 3000 = 0.35
        assertThat(resp.getAverageUtilizationRate()).isEqualByComparingTo("0.3500");
        assertThat(resp.getDailyBreakdown()).hasSize(2);
    }

    @Test
    @DisplayName("dailyUtilization_perVehicleFilter_narrowsResult")
    void perVehicleFilter_narrowsResult() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("date", Date.valueOf("2026-10-01"));
        r1.put("vehicle_id", 200L);
        r1.put("dispatched_count", 3);
        r1.put("total_weight_kg", new BigDecimal("300.00"));
        r1.put("total_volume_m3", new BigDecimal("6.00"));

        Map<String, Object> r2 = new HashMap<>();
        r2.put("date", Date.valueOf("2026-10-01"));
        r2.put("vehicle_id", 999L);
        r2.put("dispatched_count", 7);
        r2.put("total_weight_kg", new BigDecimal("900.00"));
        r2.put("total_volume_m3", new BigDecimal("12.00"));

        WmsVehicle otherVehicle = new WmsVehicle();
        otherVehicle.setId(999L);
        otherVehicle.setPlateNo("京A99999");
        otherVehicle.setMaxLoadKg(new BigDecimal("1000"));
        otherVehicle.setMaxVolumeM3(new BigDecimal("20"));

        when(orderMapper.aggregateByDateInTenant(any(), any(), anyLong()))
                .thenReturn(List.of(r1, r2));
        when(vehicleMapper.selectBatchIds(any())).thenReturn(List.of(vehicle, otherVehicle));

        List<DailyUtilizationRow> rows = service.dailyUtilization(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), 200L);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getVehicleId()).isEqualTo(200L);
        assertThat(rows.get(0).getPlateNo()).isEqualTo("京A00001");
    }

    @Test
    @DisplayName("dailyUtilization_invalidRange_throws")
    void invalidRange_throws() {
        assertThatThrownBy(() -> service.dailyUtilization(
                LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 1), null))
                .isInstanceOf(DispatchException.class)
                .hasMessageContaining("dateEnd");
    }

    @Test
    @DisplayName("dailyUtilization_rangeTooLarge_throws")
    void rangeTooLarge_throws() {
        assertThatThrownBy(() -> service.dailyUtilization(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1), null))
                .isInstanceOf(DispatchException.class)
                .hasMessageContaining("date range too large");
    }
}
