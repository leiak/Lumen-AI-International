package com.lumen.dispatch.query;

import com.lumen.dispatch.dto.UtilizationDtos.DailyUtilizationRow;
import com.lumen.dispatch.dto.UtilizationDtos.FleetUtilizationResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 车辆利用率只读查询 (Iteration 4.1 PR5).
 *
 * <p>区别于 {@code DeliveryOrderService} 这种 CRUD + 状态机服务，本接口是
 * 纯查询 + 聚合（{@code @Transactional(readOnly = true)}），不写任何业务表。
 *
 * <p>聚合口径见 {@link DailyUtilizationRow#utilizationRate}.
 */
public interface VehicleUtilizationQuery {

    /**
     * 按日 + 车辆维度返回扁平行，可选 {@code vehicleId} 过滤.
     *
     * @param dateStart inclusive 起始日期
     * @param dateEnd   inclusive 结束日期；二者跨度不允许超过 90 天
     * @param vehicleId 可选过滤；null = 全部车辆
     * @return 不为 null 的列表（无数据时为空列表）
     */
    List<DailyUtilizationRow> dailyUtilization(LocalDate dateStart, LocalDate dateEnd, Long vehicleId);

    /**
     * 车队整体利用率：顶部 totals + 每日明细.
     *
     * <p>调用方应当只关心"近 7 / 30 天"这种小窗口；超过 90 天仍然返回（不抛错），
     * 但响应会比较大；前端应主动 cap 在 90 天内.
     *
     * @return 包含 {@code dateStart}, {@code dateEnd}, {@code totalDispatched},
     *         {@code totalWeightKg}, {@code totalVolumeM3}, {@code averageUtilizationRate},
     *         {@code dailyBreakdown} 的对象
     */
    FleetUtilizationResponse fleetUtilization(LocalDate dateStart, LocalDate dateEnd);
}
