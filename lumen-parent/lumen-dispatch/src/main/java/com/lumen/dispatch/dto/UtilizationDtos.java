package com.lumen.dispatch.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 车辆利用率聚合 DTO (Iteration 4.1 PR5).
 *
 * <p>对外暴露 2 个聚合类型：
 * <ul>
 *   <li>{@link DailyUtilizationRow} — 按日 + 车辆的扁平行；用于 ProTable / 日点 {@code /utilization/daily}</li>
 *   <li>{@link FleetUtilizationResponse} — 车队整体聚合 + 每日明细；用于仪表盘 KPI 卡</li>
 * </ul>
 *
 * <p>利用率公式 (javadoc 留底，PR6 可改）：
 * <pre>
 *   utilizationRate = total_weight_kg / (vehicle.max_load_kg * 3)
 * </pre>
 * 即"假设每车每天满载 3 趟"——口径稳定的相对值，0.0~1.0 区间。
 * PR6 接入 {@code dlv.daily_trip_estimate} 配置后再换算。
 */
public final class UtilizationDtos {
    private UtilizationDtos() {}

    /**
     * 单行：某日 + 某车的聚合.
     */
    @Data
    public static class DailyUtilizationRow {
        /** 派单所属日期 (按 actual_depart_at 截断到日期). */
        private LocalDate date;

        /** 车辆 ID. */
        private Long vehicleId;

        /** 车牌号 (joined from wms_vehicle). */
        private String plateNo;

        /** 当日该车完成的派送单数 (SIGNED + EXCEPTION 都计 — 后者代表"已尝试派送但失败"). */
        private Long dispatchedCount;

        /** 当日该车装载的总重 (kg). */
        private BigDecimal totalWeightKg;

        /** 当日该车装载的总体积 (m^3). */
        private BigDecimal totalVolumeM3;

        /**
         * 利用率 (0.0 ~ 1.0). 公式：
         * {@code min(total_weight_kg / (max_load_kg * 3), 1.0)}.
         * 当 {@code max_load_kg <= 0} 或 null 时回退 0.0。
         */
        private BigDecimal utilizationRate;
    }

    /**
     * 车队整体：totals + 每日明细 (用于仪表盘顶部 KPI 卡 + 下方表格).
     */
    @Data
    public static class FleetUtilizationResponse {
        private LocalDate dateStart;
        private LocalDate dateEnd;
        private Long totalDispatched;
        private BigDecimal totalWeightKg;
        private BigDecimal totalVolumeM3;
        /** 平均利用率 (按行加权 = totalWeight / (sum(maxLoad*3))). */
        private BigDecimal averageUtilizationRate;
        private List<DailyUtilizationRow> dailyBreakdown;
    }
}
