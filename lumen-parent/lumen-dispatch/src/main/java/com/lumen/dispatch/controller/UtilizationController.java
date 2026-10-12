package com.lumen.dispatch.controller;

import com.lumen.common.api.R;
import com.lumen.dispatch.dto.UtilizationDtos.DailyUtilizationRow;
import com.lumen.dispatch.dto.UtilizationDtos.FleetUtilizationResponse;
import com.lumen.dispatch.query.VehicleUtilizationQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 车辆利用率查询 REST 控制器 (Iteration 4.1 PR5).
 *
 * <p>前缀 {@code /api/v1/dispatch/utilization}，两个端点：
 * <ul>
 *   <li>{@code GET /daily} — 按日 + 车辆维度的扁平行，可选 vehicleId 过滤</li>
 *   <li>{@code GET /fleet} — 整体 totals + 每日明细，用于仪表盘顶部 KPI 卡</li>
 * </ul>
 *
 * <p>所有端点由 {@code dispatch:dashboard:view} 守门.
 */
@RestController
@RequestMapping("/api/v1/dispatch/utilization")
@RequiredArgsConstructor
public class UtilizationController {

    private final VehicleUtilizationQuery utilizationQuery;

    @GetMapping("/daily")
    @PreAuthorize("hasAuthority('dispatch:dashboard:view')")
    public R<List<DailyUtilizationRow>> daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateEnd,
            @RequestParam(required = false) Long vehicleId) {
        return R.ok(utilizationQuery.dailyUtilization(dateStart, dateEnd, vehicleId));
    }

    @GetMapping("/fleet")
    @PreAuthorize("hasAuthority('dispatch:dashboard:view')")
    public R<FleetUtilizationResponse> fleet(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateEnd) {
        return R.ok(utilizationQuery.fleetUtilization(dateStart, dateEnd));
    }
}
