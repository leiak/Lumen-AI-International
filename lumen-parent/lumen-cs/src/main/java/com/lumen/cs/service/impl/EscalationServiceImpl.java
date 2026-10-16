package com.lumen.cs.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumen.cs.calculator.SlaCalculator;
import com.lumen.cs.calculator.TenantCalendar;
import com.lumen.cs.dto.CalendarDtos;
import com.lumen.cs.dto.EscalationDtos.SlaEventDto;
import com.lumen.cs.dto.HolidayDtos;
import com.lumen.cs.entity.SlaConfig;
import com.lumen.cs.entity.WmsDeliveryExceptionView;
import com.lumen.cs.exception.CsErrorCode;
import com.lumen.cs.exception.CsException;
import com.lumen.cs.mapper.SlaConfigMapper;
import com.lumen.cs.mapper.WmsDeliveryExceptionViewMapper;
import com.lumen.cs.service.BusinessCalendarService;
import com.lumen.cs.service.EscalationService;
import com.lumen.cs.service.HolidayService;
import com.lumen.cs.service.SlaEventService;
import com.lumen.dispatch.event.ExceptionEscalatedEvent;
import com.lumen.extension.outbox.EventBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SLA 升级扫描器实现 (Iteration 4.2 PR4).
 *
 * <p>核心算法 (per spec §5.2):
 * <ol>
 *   <li>每个 tenant 拉 {@link TenantCalendar} (日历 + 节假日)</li>
 *   <li>查 {@code wms_delivery_exception} 中 OPEN / INVESTIGATING 工单</li>
 *   <li>每条工单 (独立 transaction):
 *       <ul>
 *         <li>从 {@code t_sla_event} 找最近 L1_RESPONDED; 没有则取 {@code created_at}</li>
 *         <li>用 {@link SlaCalculator} 计算 {@code businessMinutes}</li>
 *         <li>查 {@code t_sla_config} 当前 (severity, level) 配置</li>
 *         <li>{@code businessMinutes > resolveMinutes} 且 {@code escalate_to_level != null}
 *             → 升级</li>
 *       </ul>
 *   </li>
 *   <li>写 {@code t_sla_event} (UNIQUE KEY 防并发) + 发 outbox
 *       {@link ExceptionEscalatedEvent} 通知 lumen-dispatch 更新 handler</li>
 * </ol>
 *
 * <p><b>Severity 派生:</b>
 * <pre>
 *   DAMAGE   → HIGH
 *   OVERTIME → MEDIUM
 *   REJECTED → MEDIUM
 *   ADDR_ERR → LOW
 * </pre>
 * 未映射类型抛 {@link CsErrorCode#SEVERITY_MAPPING_MISSING}.
 *
 * <p><b>Level 推断:</b> handler username 包含 {@code cs_manager} → L3,
 * 包含 {@code cs_supervisor} → L2, 其余 (含未分配) → L1.
 *
 * <p><b>事务边界:</b> 每条工单的升级是独立事务 (用 {@link TransactionTemplate}
 * 包裹), 一条失败不影响其他. {@link EventBus#publish} 是
 * {@code @Transactional(MANDATORY)} — 必须有事务上下文.
 */
@Slf4j
@Service
public class EscalationServiceImpl implements EscalationService {

    private static final String LEVEL_L1 = "L1";
    private static final String LEVEL_L2 = "L2";
    private static final String LEVEL_L3 = "L3";

    /** SLA event type: 升级决策. */
    private static final String EVENT_TYPE_ESCALATED = "ESCALATED";

    private final SlaConfigMapper configMapper;
    private final WmsDeliveryExceptionViewMapper exceptionMapper;
    private final SlaCalculator calculator;
    private final BusinessCalendarService calendarService;
    private final HolidayService holidayService;
    private final SlaEventService slaEventService;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    /**
     * 启用扫描的 tenant 列表. 默认 {@code [1L]} (dev 单租户), 生产环境由
     * {@code lumen.cs.scan-tenants} 配置注入 (PR5+ 接 sys_tenant 表后由
     * tenant registry bean 替换).
     */
    private final List<Long> scanTenants;

    public EscalationServiceImpl(SlaConfigMapper configMapper,
                                 WmsDeliveryExceptionViewMapper exceptionMapper,
                                 SlaCalculator calculator,
                                 BusinessCalendarService calendarService,
                                 HolidayService holidayService,
                                 SlaEventService slaEventService,
                                 EventBus eventBus,
                                 ObjectMapper objectMapper,
                                 PlatformTransactionManager txManager,
                                 @org.springframework.beans.factory.annotation.Value(
                                     "${lumen.cs.scan-tenants:1}") String scanTenantsCsv) {
        this.configMapper = configMapper;
        this.exceptionMapper = exceptionMapper;
        this.calculator = calculator;
        this.calendarService = calendarService;
        this.holidayService = holidayService;
        this.slaEventService = slaEventService;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.scanTenants = parseCsv(scanTenantsCsv);
    }

    private static List<Long> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of(1L);
        List<Long> result = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) result.add(Long.parseLong(t));
        }
        return result.isEmpty() ? List.of(1L) : result;
    }

    @Override
    public void scan() {
        for (Long tenantId : scanTenants) {
            try {
                scanTenant(tenantId);
            } catch (Exception e) {
                log.error("SLA scan failed for tenant {}", tenantId, e);
            }
        }
    }

    private void scanTenant(long tenantId) {
        TenantCalendar cal = loadCalendar(tenantId);
        List<WmsDeliveryExceptionView> open = exceptionMapper.selectOpenByTenant(tenantId);
        if (open.isEmpty()) return;
        log.debug("SLA scan tenant={}: {} OPEN/INVESTIGATING exceptions", tenantId, open.size());
        for (WmsDeliveryExceptionView ex : open) {
            try {
                tx.executeWithoutResult(status -> checkOne(cal, ex));
            } catch (Exception e) {
                log.warn("Escalation check failed: exception={} type={}",
                    ex.getId(), ex.getType(), e);
            }
        }
    }

    private void checkOne(TenantCalendar cal, WmsDeliveryExceptionView ex) {
        String severity;
        try {
            severity = deriveSeverity(ex.getType());
        } catch (CsException e) {
            log.warn("Severity mapping missing for type={} (exception={})",
                ex.getType(), ex.getId());
            return;
        }
        String currentLevel = inferLevel(ex.getHandler());
        SlaConfig config = findConfig(ex.getTenantId(), severity, currentLevel);
        if (config == null || config.getEscalateToLevel() == null) {
            // 没配 config 或已到 L3 (escalate_to_level=NULL) — 不升级
            return;
        }

        LocalDateTime start = resolveStart(ex);
        long elapsed = calculator.businessMinutes(cal, start, LocalDateTime.now());
        if (elapsed <= config.getResolveMinutes()) {
            // 还没超期
            return;
        }

        // 1. 写 t_sla_event (UNIQUE KEY 防并发). 仅在新插入时才发出 outbox —
        // 否则两个 scheduler pod 同时扫描同一异常会导致重复 outbox, 而
        // OutboxDispatcher 并未按 eventId 去重 (重复 handler 更新会触发).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("businessMinutesElapsed", elapsed);
        payload.put("resolveTargetMinutes", config.getResolveMinutes());
        payload.put("fromLevel", currentLevel);
        payload.put("toLevel", config.getEscalateToLevel());
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            payloadJson = "{}";
        }
        Optional<SlaEventDto> inserted = slaEventService.record(
            ex.getTenantId(), ex.getId(), EVENT_TYPE_ESCALATED,
            currentLevel, severity, LocalDateTime.now(), payloadJson);
        if (inserted.isEmpty()) {
            // 别的 scheduler pod 已经写过同一 (exception, eventType, level) 行,
            // 跳过 outbox 发布, 避免下游 lumen-dispatch 收到重复 handler 更新.
            log.debug("Skip outbox publish: SLA event duplicate suppressed for exception={} type={} level={}",
                ex.getId(), EVENT_TYPE_ESCALATED, currentLevel);
            return;
        }

        // 2. 发 outbox ExceptionEscalatedEvent → lumen-dispatch 收到后更新 handler
        String newHandler = pickUserForLevel(ex.getTenantId(), config.getEscalateToLevel());
        eventBus.publish(new ExceptionEscalatedEvent(
            ex.getTenantId(), ex.getId(),
            currentLevel, config.getEscalateToLevel(),
            severity, elapsed, newHandler
        ));
    }

    /**
     * type → severity 派生 (硬编码, 见 spec §3.5).
     */
    private String deriveSeverity(String type) {
        if (type == null) {
            throw new CsException(CsErrorCode.SEVERITY_MAPPING_MISSING, "null");
        }
        return switch (type) {
            case "DAMAGE" -> "HIGH";
            case "OVERTIME", "REJECTED" -> "MEDIUM";
            case "ADDR_ERR" -> "LOW";
            default -> throw new CsException(CsErrorCode.SEVERITY_MAPPING_MISSING, type);
        };
    }

    /**
     * handler username → 当前 level. 没 handler 视为 L1 (刚创建).
     * 简化规则 (PR4 + PR5): handler 字符串含 {@code cs_manager} → L3,
     * 含 {@code cs_supervisor} → L2, 其余 (e.g. "客服Alice") → L1.
     */
    private String inferLevel(String handlerUsername) {
        if (handlerUsername == null || handlerUsername.isBlank()) {
            return LEVEL_L1;
        }
        if (handlerUsername.contains("cs_manager")) return LEVEL_L3;
        if (handlerUsername.contains("cs_supervisor")) return LEVEL_L2;
        return LEVEL_L1;
    }

    /**
     * SLA 计时起点: 最近一次 L1_RESPONDED (业务响应) 或工单创建时间 (未响应).
     */
    private LocalDateTime resolveStart(WmsDeliveryExceptionView ex) {
        SlaEventDto responded = slaEventService.getLatestResponse(ex.getTenantId(), ex.getId());
        return responded != null ? responded.occurredAt() : ex.getCreatedAt();
    }

    private SlaConfig findConfig(Long tenantId, String severity, String level) {
        return configMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
            .eq(SlaConfig::getTenantId, tenantId)
            .eq(SlaConfig::getSeverity, severity)
            .eq(SlaConfig::getLevel, level)
            .last("LIMIT 1"));
    }

    /**
     * 拼装 tenant 的 {@link TenantCalendar}: weekday -> [TimeRange], 节假日 set.
     * PR4 取所有记录 (size=200), 生产环境 tenant 通常 < 100 条.
     */
    private TenantCalendar loadCalendar(long tenantId) {
        Map<Integer, List<TenantCalendar.TimeRange>> weekdayRanges = new HashMap<>();
        try {
            List<CalendarDtos.GetResponse> calEntries = calendarService
                .list(tenantId, new CalendarDtos.ListRequest(1, 200))
                .items();
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
                .list(tenantId, new HolidayDtos.ListRequest(1, 200))
                .items();
            holidays = holEntries.stream()
                .map(HolidayDtos.GetResponse::holidayDate)
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Failed to load holidays for tenant={}, fallback to empty", tenantId, e);
        }
        return new TenantCalendar(weekdayRanges, holidays);
    }

    /**
     * 简化: 返回形如 {@code "system_{tenant}_{level}"}. PR4 阶段未接入
     * user-role 表, 由 PR5 引入 3 个 cs_* 角色 + role_assignment 后改为
     * "查 sys_user by role.code = 'cs_supervisor' AND tenant_id = ? LIMIT 1".
     */
    private String pickUserForLevel(long tenantId, String level) {
        return "system_" + tenantId + "_" + level;
    }
}
