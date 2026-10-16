package com.lumen.cs.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.cs.calculator.SlaCalculator;
import com.lumen.cs.calculator.TenantCalendar;
import com.lumen.cs.dto.CalendarDtos;
import com.lumen.cs.dto.EscalationDtos.ExceptionSlaView;
import com.lumen.cs.dto.EscalationDtos.SlaEventDto;
import com.lumen.cs.dto.HolidayDtos;
import com.lumen.cs.entity.SlaConfig;
import com.lumen.cs.entity.WmsDeliveryExceptionView;
import com.lumen.cs.mapper.SlaConfigMapper;
import com.lumen.cs.service.BusinessCalendarService;
import com.lumen.cs.service.HolidayService;
import com.lumen.cs.service.SlaEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CS 异常工单 → SLA 视角视图 聚合服务 (Iteration 4.2 PR6 finalize fix).
 *
 * <p>本服务把 {@link WmsDeliveryExceptionView} 原始行 + 3 张下游表 / 配置 服务聚合成
 * {@link ExceptionSlaView}, 供 {@code CsExceptionController} 返回给 FE 客服工作台:
 * <ol>
 *   <li>{@code wms_delivery_exception} — 原始工单行 (type/status/handler/createdAt)</li>
 *   <li>{@code t_sla_event}            — SLA 事件历史 ({@link SlaEventService#listByException});
 *       最新一次 L1_RESPONDED ({@link SlaEventService#getLatestResponse}) 用作计时起点</li>
 *   <li>{@code t_sla_config}           — 当前 (severity, level) 对应的 {@code resolveMinutes}
 *       ({@link SlaConfigMapper#selectOne})</li>
 *   <li>{@link SlaCalculator} + {@link TenantCalendar} — 按业务时间计算 {@code businessMinutesElapsed}</li>
 * </ol>
 *
 * <p><b>type → severity 派生:</b>
 * <pre>
 *   DAMAGE   → HIGH
 *   OVERTIME → MEDIUM
 *   REJECTED → MEDIUM
 *   ADDR_ERR → LOW
 * </pre>
 * 未知 type 抛 {@link IllegalArgumentException} (供 controller 翻译成 4xx). 这里同
 * {@link EscalationServiceImpl#deriveSeverity} 的硬编码保持一致, 但作为 public static 暴露,
 * 以便 {@code typesForSeverity(...)} 反向映射复用. (PR6 之后若新加 type, 两边需同步.)
 *
 * <p><b>severity → types 反向映射:</b> 给 FE 的 severity 过滤器 (HIGH / MEDIUM / LOW)
 * 翻译成 {@code wms_delivery_exception.type} 的 IN 子句. 未知 severity 返空 set,
 * controller 据此跳过 mapper, 直接返空 PageResult.
 *
 * <p><b>handler → level 推断:</b> 与 {@link EscalationServiceImpl#inferLevel} 同算法, 暴露
 * 静态是为了 controller 不必再去 spawn 一个可注入 service 来获得级别.
 *
 * <p><b>escalationStatus 阈值:</b> 与 FE 端 {@code computeEscalationStatus} 保持一致
 * (80% → WARNING, 100% → BREACHED), 这里用同样的公式, 因为 FE 也用这两条 row
 * ({@code businessMinutesElapsed} / {@code resolveTargetMinutes}) 渲染 {@code SlaCountdownTag}.
 *
 * <p><b>并发 / 事务:</b> 服务方法无状态, 不参与事务 (只读). Spring 默认注入的 service 全部
 * 由 service 内部自行管理, 这里不强制 {@code @Transactional(readOnly=true)}.
 */
@Slf4j
@Service
public class ExceptionSlaAggregationService {

    /**
     * type → severity 派生 (硬编码, 与 {@link EscalationServiceImpl} 同步).
     * 暴露为 {@code public static} 以便 controller 的 severity filter 反向 map 复用 + 单测覆盖.
     */
    private static final Map<String, String> TYPE_TO_SEVERITY = Map.of(
        "DAMAGE", "HIGH",
        "OVERTIME", "MEDIUM",
        "REJECTED", "MEDIUM",
        "ADDR_ERR", "LOW"
    );

    /**
     * severity → types 反向映射 (给 FE severity filter 用).
     * <p>HIGH → {DAMAGE}; MEDIUM → {OVERTIME, REJECTED}; LOW → {ADDR_ERR}.
     */
    private static final Map<String, Set<String>> SEVERITY_TO_TYPES = Map.of(
        "HIGH", Set.of("DAMAGE"),
        "MEDIUM", Set.of("OVERTIME", "REJECTED"),
        "LOW", Set.of("ADDR_ERR")
    );

    private final SlaConfigMapper slaConfigMapper;
    private final SlaEventService slaEventService;
    private final BusinessCalendarService businessCalendarService;
    private final HolidayService holidayService;
    private final SlaCalculator slaCalculator;

    public ExceptionSlaAggregationService(SlaConfigMapper slaConfigMapper,
                                          SlaEventService slaEventService,
                                          BusinessCalendarService businessCalendarService,
                                          HolidayService holidayService,
                                          SlaCalculator slaCalculator) {
        this.slaConfigMapper = slaConfigMapper;
        this.slaEventService = slaEventService;
        this.businessCalendarService = businessCalendarService;
        this.holidayService = holidayService;
        this.slaCalculator = slaCalculator;
    }

    // ============================================================
    // Public static helpers — 反向映射 + 阈值 (无副作用, 便于复用/单测)
    // ============================================================

    /**
     * {@link WmsDeliveryExceptionView#getType} → severity.
     *
     * @throws IllegalArgumentException 未知 type (controller 翻译成 4xx)
     */
    public static String deriveSeverity(String type) {
        if (type == null) {
            throw new IllegalArgumentException("ExceptionSlaAggregationService: type must not be null");
        }
        String severity = TYPE_TO_SEVERITY.get(type);
        if (severity == null) {
            throw new IllegalArgumentException(
                "ExceptionSlaAggregationService: unknown exception type '" + type
                    + "' (expected DAMAGE / OVERTIME / REJECTED / ADDR_ERR)");
        }
        return severity;
    }

    /**
     * FE severity filter (HIGH / MEDIUM / LOW) → {@code wms_delivery_exception.type}
     * 集合 (供 mapper {@code .in(...)} 用).
     *
     * @return 不可变 set; 未知 severity 返空 (controller 据此短路返空 PageResult)
     */
    public static Set<String> typesForSeverity(String severity) {
        if (severity == null) return Set.of();
        return SEVERITY_TO_TYPES.getOrDefault(severity.toUpperCase(), Set.of());
    }

    /**
     * handler username → 当前升级 level ({@code "L1" / "L2" / "L3"}).
     * 与 {@link EscalationServiceImpl#inferLevel} 同算法: handler 字符串含
     * {@code cs_manager} → L3, 含 {@code cs_supervisor} → L2, 其余 (含 null / blank) → L1.
     */
    public static String inferLevel(String handlerUsername) {
        if (handlerUsername == null || handlerUsername.isBlank()) {
            return "L1";
        }
        if (handlerUsername.contains("cs_manager")) return "L3";
        if (handlerUsername.contains("cs_supervisor")) return "L2";
        return "L1";
    }

    /**
     * {@code escalationStatus} 阈值判断 — 与 FE 端 {@code computeEscalationStatus} 一致
     * ({@code < 0.8 → OK}, {@code ≥ 0.8 → WARNING}, {@code ≥ 1.0 → BREACHED}).
     * {@code target <= 0} 时返 {@code "OK"} (防御: 没配 config 时不爆).
     */
    public static String computeEscalationStatus(long elapsedMinutes, long targetMinutes) {
        if (targetMinutes <= 0) return "OK";
        double ratio = (double) elapsedMinutes / targetMinutes;
        if (ratio >= 1.0) return "BREACHED";
        if (ratio >= 0.8) return "WARNING";
        return "OK";
    }

    // ============================================================
    // 核心: 单行 → ExceptionSlaView
    // ============================================================

    /**
     * 把一行 {@link WmsDeliveryExceptionView} 转成 {@link ExceptionSlaView}.
     *
     * <p>算法:
     * <ol>
     *   <li>派生 severity + currentLevel (静态, 与 {@link EscalationServiceImpl} 同步)</li>
     *   <li>最近一次 {@code L1_RESPONDED} → 没则用 {@code createdAt}, 作 SLA 计时起点</li>
     *   <li>{@link SlaCalculator#businessMinutes} 算 {@code businessMinutesElapsed} (走
     *       {@link TenantCalendar} 跨日历+节假日)</li>
     *   <li>查 {@code t_sla_config} (severity, level) → {@code resolveMinutes},
     *       没有则 {@code resolveTargetMinutes=0} + {@code escalationStatus=OK} (前端会读
     *       0 target 走 "OK" 分支)</li>
     *   <li>拉该 exception 的全 {@code t_sla_event} 按 {@code occurred_at} 升序, 作为
     *       {@code slaEvents} 列表</li>
     * </ol>
     */
    public ExceptionSlaView toView(WmsDeliveryExceptionView row) {
        long tenantId = row.getTenantId();
        Long exceptionId = row.getId();
        String severity = deriveSeverity(row.getType());
        String currentLevel = inferLevel(row.getHandler());

        // 1) SLA 计时起点
        SlaEventDto latestResponse = slaEventService.getLatestResponse(tenantId, exceptionId);
        LocalDateTime start = latestResponse != null ? latestResponse.occurredAt() : row.getCreatedAt();

        // 2) 业务分钟数 (要 calendar + holiday 才能算 — 仿 EscalationServiceImpl 写法)
        long elapsed = slaCalculator.businessMinutes(
            loadCalendar(tenantId), start, LocalDateTime.now());

        // 3) 当前 (severity, level) 的 resolve 配置
        Long target = lookupResolveMinutes(tenantId, severity, currentLevel);
        long resolveTargetMinutes = target != null ? target : 0L;
        String escalationStatus = computeEscalationStatus(elapsed, resolveTargetMinutes);

        // 4) sla_events 列表
        List<SlaEventDto> events = slaEventService.listByException(tenantId, exceptionId);

        return new ExceptionSlaView(
            exceptionId,
            row.getType(),
            row.getStatus(),
            row.getHandler(),
            currentLevel,
            severity,
            elapsed,
            resolveTargetMinutes,
            escalationStatus,
            events
        );
    }

    // ============================================================
    // Private helpers
    // ============================================================

    private Long lookupResolveMinutes(Long tenantId, String severity, String level) {
        SlaConfig c = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
            .eq(SlaConfig::getTenantId, tenantId)
            .eq(SlaConfig::getSeverity, severity)
            .eq(SlaConfig::getLevel, level)
            .last("LIMIT 1"));
        return c != null ? c.getResolveMinutes().longValue() : null;
    }

    /**
     * 拼装 tenant 的 {@link TenantCalendar}: weekday → [TimeRange], 节假日 set.
     * <p>失败回退到空 calendar (而非抛) — 让 {@link SlaCalculator} 算出 0,
     * controller 仍能 200 渲染默认态. 与 {@link EscalationServiceImpl#loadCalendar}
     * 巧合一致.
     */
    private TenantCalendar loadCalendar(long tenantId) {
        Map<Integer, List<TenantCalendar.TimeRange>> weekdayRanges = new HashMap<>();
        try {
            List<CalendarDtos.GetResponse> calEntries = businessCalendarService
                .list(tenantId, new CalendarDtos.ListRequest(1, 200)).items();
            for (CalendarDtos.GetResponse c : calEntries) {
                weekdayRanges.computeIfAbsent(c.weekday(), k -> new ArrayList<>())
                    .add(new TenantCalendar.TimeRange(c.startTime(), c.endTime()));
            }
        } catch (Exception e) {
            log.warn("Failed to load business calendar for tenant={}, fallback to empty", tenantId, e);
        }

        Set<LocalDate> holidays = Set.of();
        try {
            List<HolidayDtos.GetResponse> holEntries = holidayService
                .list(tenantId, new HolidayDtos.ListRequest(1, 200)).items();
            holidays = holEntries.stream()
                .map(HolidayDtos.GetResponse::holidayDate)
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Failed to load holidays for tenant={}, fallback to empty", tenantId, e);
        }
        return new TenantCalendar(weekdayRanges, holidays);
    }
}
