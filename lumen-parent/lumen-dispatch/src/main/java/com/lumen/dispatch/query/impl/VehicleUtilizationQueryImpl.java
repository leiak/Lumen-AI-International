package com.lumen.dispatch.query.impl;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.dto.UtilizationDtos.DailyUtilizationRow;
import com.lumen.dispatch.dto.UtilizationDtos.FleetUtilizationResponse;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.exception.DispatchException;
import com.lumen.dispatch.error.DispatchErrorCode;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.mapper.WmsVehicleMapper;
import com.lumen.dispatch.query.VehicleUtilizationQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link VehicleUtilizationQuery} 实现 (Iteration 4.1 PR5).
 *
 * <p>采用"DB 聚合 + Java enrichment"两步走：
 * <ol>
 *   <li>由 {@link WmsDeliveryOrderMapper#aggregateByDateInTenant} 在 MySQL 端
 *       按 (date, vehicle_id) 分组聚合 — 避免把全表行拉回 Java。</li>
 *   <li>Service 端 join {@link WmsVehicle} 取车牌 + 容量，再算 utilization rate。</li>
 * </ol>
 *
 * <p>利用率公式 (与 {@link DailyUtilizationRow} 注释保持一致)：
 * <pre>
 *   utilizationRate = min(total_weight_kg / (vehicle.max_load_kg * 3), 1.0)
 * </pre>
 * 假设每车每天满载 3 趟作为归一化分母. 0.0 ~ 1.0 区间，精度 4 位小数.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleUtilizationQueryImpl implements VehicleUtilizationQuery {

    /**
     * 利用率分母倍率：每车每天"满载 3 趟"假设. 后续可改为读取 dlv 配置.
     */
    private static final int DAILY_TRIP_ESTIMATE = 3;

    /**
     * 仪表盘查询允许的最大日期跨度 (90 天). 超过此值抛业务异常以保护内存.
     */
    private static final long MAX_RANGE_DAYS = 90L;

    private final WmsDeliveryOrderMapper orderMapper;
    private final WmsVehicleMapper vehicleMapper;

    @Override
    @Transactional(readOnly = true)
    public List<DailyUtilizationRow> dailyUtilization(LocalDate dateStart, LocalDate dateEnd, Long vehicleId) {
        validateRange(dateStart, dateEnd);

        Long tenantId = TenantContext.require();
        List<Map<String, Object>> raw = orderMapper.aggregateByDateInTenant(dateStart, dateEnd, tenantId);

        if (raw.isEmpty()) {
            return List.of();
        }

        // 1) 收集所有 vehicle_id, 一次批量查
        Set<Long> vehicleIds = raw.stream()
                .map(row -> ((Number) row.get("vehicle_id")).longValue())
                .filter(id -> vehicleId == null || id.equals(vehicleId))
                .collect(Collectors.toSet());
        if (vehicleIds.isEmpty()) {
            return List.of();
        }
        Map<Long, WmsVehicle> vehicleById = vehicleMapper.selectBatchIds(vehicleIds).stream()
                .collect(Collectors.toMap(WmsVehicle::getId, v -> v));

        // 2) 装配
        List<DailyUtilizationRow> rows = new ArrayList<>(raw.size());
        for (Map<String, Object> r : raw) {
            Long vId = ((Number) r.get("vehicle_id")).longValue();
            if (vehicleId != null && !vehicleId.equals(vId)) {
                continue;
            }
            WmsVehicle v = vehicleById.get(vId);

            DailyUtilizationRow row = new DailyUtilizationRow();
            row.setDate(toLocalDate(r.get("date")));
            row.setVehicleId(vId);
            row.setPlateNo(v != null ? v.getPlateNo() : "(unknown)");
            row.setDispatchedCount(((Number) r.get("dispatched_count")).longValue());
            row.setTotalWeightKg(toBigDecimal(r.get("total_weight_kg")));
            row.setTotalVolumeM3(toBigDecimal(r.get("total_volume_m3")));
            row.setUtilizationRate(computeRate(row.getTotalWeightKg(), v));
            rows.add(row);
        }
        return rows;
    }

    @Override
    @Transactional(readOnly = true)
    public FleetUtilizationResponse fleetUtilization(LocalDate dateStart, LocalDate dateEnd) {
        validateRange(dateStart, dateEnd);

        List<DailyUtilizationRow> daily = dailyUtilization(dateStart, dateEnd, null);

        FleetUtilizationResponse resp = new FleetUtilizationResponse();
        resp.setDateStart(dateStart);
        resp.setDateEnd(dateEnd);
        resp.setDailyBreakdown(daily);

        // totals
        long totalDispatched = daily.stream().mapToLong(DailyUtilizationRow::getDispatchedCount).sum();
        BigDecimal totalWeight = daily.stream()
                .map(DailyUtilizationRow::getTotalWeightKg)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalVolume = daily.stream()
                .map(DailyUtilizationRow::getTotalVolumeM3)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // average rate = total_weight / sum(max_load_kg * 3) over distinct (date, vehicle) rows
        BigDecimal denomSum = BigDecimal.ZERO;
        Set<String> seen = new HashSet<>();
        for (DailyUtilizationRow r : daily) {
            String key = r.getDate() + "#" + r.getVehicleId();
            if (!seen.add(key)) continue;
            WmsVehicle v = vehicleMapper.selectById(r.getVehicleId());
            if (v != null && v.getMaxLoadKg() != null && v.getMaxLoadKg().signum() > 0) {
                denomSum = denomSum.add(v.getMaxLoadKg().multiply(BigDecimal.valueOf(DAILY_TRIP_ESTIMATE)));
            }
        }
        BigDecimal avgRate = BigDecimal.ZERO;
        if (denomSum.signum() > 0) {
            avgRate = totalWeight.divide(denomSum, 4, RoundingMode.HALF_UP);
            if (avgRate.compareTo(BigDecimal.ONE) > 0) {
                avgRate = BigDecimal.ONE;
            }
        }

        resp.setTotalDispatched(totalDispatched);
        resp.setTotalWeightKg(totalWeight);
        resp.setTotalVolumeM3(totalVolume);
        resp.setAverageUtilizationRate(avgRate);
        return resp;
    }

    // ================================================================
    // helpers
    // ================================================================

    /**
     * 校验日期区间. 跨度过大抛业务异常，防止 OOM.
     */
    private static void validateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new DispatchException(DispatchErrorCode.INVALID_DATE_RANGE,
                    "dateStart/dateEnd required");
        }
        if (end.isBefore(start)) {
            throw new DispatchException(DispatchErrorCode.INVALID_DATE_RANGE,
                    "dateEnd must be on or after dateStart");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new DispatchException(DispatchErrorCode.INVALID_DATE_RANGE,
                    "date range too large: " + days + " days (max " + MAX_RANGE_DAYS + ")");
        }
    }

    /**
     * 单行利用率计算. 公式:
     * {@code min(weight / (maxLoad * 3), 1.0)}.
     */
    private static BigDecimal computeRate(BigDecimal weight, WmsVehicle v) {
        if (v == null || v.getMaxLoadKg() == null || v.getMaxLoadKg().signum() <= 0 || weight == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal denom = v.getMaxLoadKg().multiply(BigDecimal.valueOf(DAILY_TRIP_ESTIMATE));
        BigDecimal rate = weight.divide(denom, 4, RoundingMode.HALF_UP);
        if (rate.compareTo(BigDecimal.ONE) > 0) {
            rate = BigDecimal.ONE;
        }
        return rate;
    }

    private static LocalDate toLocalDate(Object sqlDate) {
        if (sqlDate == null) return null;
        if (sqlDate instanceof Date d) return d.toLocalDate();
        if (sqlDate instanceof LocalDate ld) return ld;
        if (sqlDate instanceof java.util.Date ud) return ud.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return LocalDate.parse(sqlDate.toString());
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(o.toString());
    }
}
