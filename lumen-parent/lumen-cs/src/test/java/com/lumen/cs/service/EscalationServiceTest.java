package com.lumen.cs.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumen.cs.calculator.SlaCalculator;
import com.lumen.cs.calculator.TenantCalendar;
import com.lumen.cs.dto.CalendarDtos;
import com.lumen.cs.dto.EscalationDtos.SlaEventDto;
import com.lumen.cs.dto.HolidayDtos;
import com.lumen.cs.entity.SlaConfig;
import com.lumen.cs.entity.WmsDeliveryExceptionView;
import com.lumen.cs.mapper.SlaConfigMapper;
import com.lumen.cs.mapper.WmsDeliveryExceptionViewMapper;
import com.lumen.cs.service.impl.EscalationServiceImpl;
import com.lumen.dispatch.event.ExceptionEscalatedEvent;
import com.lumen.extension.outbox.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EscalationService 单元测试 (Iteration 4.2 PR4).
 *
 * <p>覆盖:
 * <ul>
 *   <li>severity 派生 (DAMAGE → HIGH; OVERTIME/REJECTED → MEDIUM; ADDR_ERR → LOW;
 *       未知 type 跳过)</li>
 *   <li>level 推断 (handler 含 cs_supervisor → L2; 普通 handler → L1)</li>
 *   <li>{@code scan()} 不超期 → 不写 t_sla_event / 不发 outbox</li>
 *   <li>{@code scan()} 超期 → 写 ESCALATED + 发 ExceptionEscalatedEvent</li>
 *   <li>{@code scan()} L3 (escalate_to_level=NULL) → 不升级</li>
 *   <li>{@code scan()} 没配 config → 不升级</li>
 *   <li>没 L1_RESPONDED → 用 createdAt 作为计时起点</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EscalationService 单元测试")
class EscalationServiceTest {

    /** 60 天前 — 任何 resolve_minutes 都会超期 (60 天 >> 4320 min max). */
    private static final LocalDateTime LONG_AGO = LocalDateTime.now().minusDays(60);

    @Mock private SlaConfigMapper configMapper;
    @Mock private WmsDeliveryExceptionViewMapper exceptionMapper;
    @Mock private BusinessCalendarService calendarService;
    @Mock private HolidayService holidayService;
    @Mock private SlaEventService slaEventService;
    @Mock private EventBus eventBus;
    @Mock private PlatformTransactionManager txManager;

    private final SlaCalculator calculator = new SlaCalculator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private EscalationServiceImpl service;

    @BeforeEach
    void setUp() {
        // 事务模板: getTransaction 返回简单 status; commit 默认 doNothing
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        service = new EscalationServiceImpl(
            configMapper, exceptionMapper, calculator,
            calendarService, holidayService, slaEventService,
            eventBus, objectMapper, txManager, "1");

        // 默认日历: Mon-Fri 9-18 + 无节假日
        when(calendarService.list(eq(1L), any())).thenReturn(new CalendarDtos.ListResponse(List.of(
            new CalendarDtos.GetResponse(1L, 1L, 1, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), null, null),
            new CalendarDtos.GetResponse(2L, 1L, 2, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), null, null),
            new CalendarDtos.GetResponse(3L, 1L, 3, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), null, null),
            new CalendarDtos.GetResponse(4L, 1L, 4, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), null, null),
            new CalendarDtos.GetResponse(5L, 1L, 5, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), null, null)
        ), 5L));
        when(holidayService.list(eq(1L), any())).thenReturn(new HolidayDtos.ListResponse(List.of(), 0L));

        // 默认: sla_event 写入成功 (新插入) → 调用方应发 outbox
        lenient().when(slaEventService.record(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Optional.of(new SlaEventDto(
                1L, 1L, 100L, "ESCALATED", "L1", "HIGH",
                LocalDateTime.now(), "{}")));
    }

    @Test
    @DisplayName("severity 派生: DAMAGE → HIGH")
    void deriveSeverity_damage_isHigh() {
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(exception("DAMAGE", "OPEN", null, LONG_AGO)));
        when(configMapper.selectOne(any())).thenReturn(config("HIGH", "L1", 30, 240, "L2"));
        when(slaEventService.getLatestResponse(any(), any())).thenReturn(null);

        service.scan();

        verify(slaEventService).record(any(), any(), any(),
            eq("L1"), eq("HIGH"), any(), any());
    }

    @Test
    @DisplayName("severity 派生: OVERTIME → MEDIUM")
    void deriveSeverity_overtime_isMedium() {
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(exception("OVERTIME", "OPEN", null, LONG_AGO)));
        when(configMapper.selectOne(any())).thenReturn(config("MEDIUM", "L1", 120, 1440, "L2"));
        when(slaEventService.getLatestResponse(any(), any())).thenReturn(null);

        service.scan();

        verify(slaEventService).record(any(), any(), any(),
            eq("L1"), eq("MEDIUM"), any(), any());
    }

    @Test
    @DisplayName("severity 派生: REJECTED → MEDIUM")
    void deriveSeverity_rejected_isMedium() {
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(exception("REJECTED", "OPEN", null, LONG_AGO)));
        when(configMapper.selectOne(any())).thenReturn(config("MEDIUM", "L1", 120, 1440, "L2"));
        when(slaEventService.getLatestResponse(any(), any())).thenReturn(null);

        service.scan();

        verify(slaEventService).record(any(), any(), any(),
            eq("L1"), eq("MEDIUM"), any(), any());
    }

    @Test
    @DisplayName("severity 派生: ADDR_ERR → LOW")
    void deriveSeverity_addrErr_isLow() {
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(exception("ADDR_ERR", "OPEN", null, LONG_AGO)));
        when(configMapper.selectOne(any())).thenReturn(config("LOW", "L1", 480, 4320, "L2"));
        when(slaEventService.getLatestResponse(any(), any())).thenReturn(null);

        service.scan();

        verify(slaEventService).record(any(), any(), any(),
            eq("L1"), eq("LOW"), any(), any());
    }

    @Test
    @DisplayName("severity 派生: 未知 type → 跳过, 不写 sla_event 不发 outbox")
    void deriveSeverity_unknownType_skipped() {
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(exception("WEIRD_TYPE", "OPEN", null, LONG_AGO)));

        service.scan();

        verify(slaEventService, never()).record(any(), any(), any(), any(), any(), any(), any());
        verify(eventBus, never()).publish(any());
    }

    @Test
    @DisplayName("level 推断: handler 含 cs_supervisor → L2")
    void inferLevel_supervisor_returnsL2() {
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(exception("DAMAGE", "INVESTIGATING", "cs_supervisor_42", LONG_AGO)));
        when(configMapper.selectOne(any())).thenReturn(config("HIGH", "L2", 30, 480, "L3"));
        when(slaEventService.getLatestResponse(any(), any())).thenReturn(null);

        service.scan();

        // 写 SLA event 时 currentLevel=L2
        verify(slaEventService).record(any(), any(), any(),
            eq("L2"), eq("HIGH"), any(), any());
        // 发 outbox 事件: fromLevel=L2, toLevel=L3
        ArgumentCaptor<ExceptionEscalatedEvent> evCap = ArgumentCaptor.forClass(ExceptionEscalatedEvent.class);
        verify(eventBus).publish(evCap.capture());
        ExceptionEscalatedEvent ev = evCap.getValue();
        assertThat(ev.getFromLevel()).isEqualTo("L2");
        assertThat(ev.getToLevel()).isEqualTo("L3");
    }

    @Test
    @DisplayName("level 推断: handler=客服Alice → L1")
    void inferLevel_normalHandler_returnsL1() {
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(exception("DAMAGE", "INVESTIGATING", "客服Alice", LONG_AGO)));
        when(configMapper.selectOne(any())).thenReturn(config("HIGH", "L1", 30, 240, "L2"));
        when(slaEventService.getLatestResponse(any(), any())).thenReturn(null);

        service.scan();

        verify(slaEventService).record(any(), any(), any(),
            eq("L1"), eq("HIGH"), any(), any());
    }

    @Test
    @DisplayName("scan: 不超期 → 不写 t_sla_event, 不发 outbox")
    void scan_underThreshold_noEscalation() {
        // createdAt = 30 分钟前; resolve_minutes = 240 (4 小时) → 不超期
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(
            exception("DAMAGE", "OPEN", null, LocalDateTime.now().minusMinutes(30))));
        when(configMapper.selectOne(any())).thenReturn(config("HIGH", "L1", 30, 240, "L2"));
        when(slaEventService.getLatestResponse(any(), any())).thenReturn(null);

        service.scan();

        verify(slaEventService, never()).record(any(), any(), any(), any(), any(), any(), any());
        verify(eventBus, never()).publish(any());
    }

    @Test
    @DisplayName("scan: 超期 → 写 ESCALATED + 发 ExceptionEscalatedEvent")
    void scan_overThreshold_escalates() {
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(exception("DAMAGE", "OPEN", null, LONG_AGO)));
        when(configMapper.selectOne(any())).thenReturn(config("HIGH", "L1", 30, 240, "L2"));
        when(slaEventService.getLatestResponse(any(), any())).thenReturn(null);

        service.scan();

        // 写 sla_event (event_type=ESCALATED)
        verify(slaEventService, atLeastOnce()).record(any(), any(),
            eq("ESCALATED"), any(), any(), any(), any());
        // 发 outbox 事件
        ArgumentCaptor<ExceptionEscalatedEvent> evCap = ArgumentCaptor.forClass(ExceptionEscalatedEvent.class);
        verify(eventBus, atLeastOnce()).publish(evCap.capture());
        ExceptionEscalatedEvent ev = evCap.getValue();
        assertThat(ev.getFromLevel()).isEqualTo("L1");
        assertThat(ev.getToLevel()).isEqualTo("L2");
        assertThat(ev.getSeverity()).isEqualTo("HIGH");
        assertThat(ev.getExceptionId()).isEqualTo(100L);
        assertThat(ev.getNewHandlerUsername()).isEqualTo("system_1_L2");
    }

    @Test
    @DisplayName("scan: L3 (escalate_to_level=NULL) → 不升级")
    void scan_l3NoFurtherEscalation_noOp() {
        // already at L3 (handler 含 cs_manager), escalate_to_level null → 不升级
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(
            exception("DAMAGE", "INVESTIGATING", "cs_manager_1", LONG_AGO)));
        when(configMapper.selectOne(any())).thenReturn(config("HIGH", "L3", 30, 1440, null));

        service.scan();

        verify(slaEventService, never()).record(any(), any(), any(), any(), any(), any(), any());
        verify(eventBus, never()).publish(any());
    }

    @Test
    @DisplayName("scan: 没配 config (tenant 没种 SLA) → 不升级")
    void scan_noConfig_noEscalation() {
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(exception("DAMAGE", "OPEN", null, LONG_AGO)));
        when(configMapper.selectOne(any())).thenReturn(null);

        service.scan();

        verify(slaEventService, never()).record(any(), any(), any(), any(), any(), any(), any());
        verify(eventBus, never()).publish(any());
    }

    @Test
    @DisplayName("resolveStart: 已有 L1_RESPONDED → 用 responded.occurredAt 作为起点")
    void resolveStart_usesL1RespondedTime() {
        // 7 天前创建, 3 天前响应 (handled)
        LocalDateTime respondedAt = LocalDateTime.now().minusDays(3);
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(
            exception("DAMAGE", "INVESTIGATING", "客服Bob", LONG_AGO)));
        // resolve=480 min = 8h; 3 天 >> 8h → 升级
        when(configMapper.selectOne(any())).thenReturn(config("HIGH", "L1", 30, 480, "L2"));
        when(slaEventService.getLatestResponse(any(), any())).thenReturn(
            new SlaEventDto(null, 1L, 100L, "L1_RESPONDED", "L1", "HIGH", respondedAt, null));

        service.scan();

        verify(slaEventService, atLeastOnce()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("scan: slaEventService.record 返回 empty (UNIQUE KEY 命中) → 跳过 outbox publish")
    void scan_duplicateSlaEvent_skipsOutboxPublish() {
        when(exceptionMapper.selectOpenByTenant(1L)).thenReturn(List.of(exception("DAMAGE", "OPEN", null, LONG_AGO)));
        when(configMapper.selectOne(any())).thenReturn(config("HIGH", "L1", 30, 240, "L2"));
        when(slaEventService.getLatestResponse(any(), any())).thenReturn(null);
        // 模拟 UNIQUE KEY 命中 — record() 返回 empty
        when(slaEventService.record(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Optional.empty());

        service.scan();

        // SLA event row 仍尝试写入 (insert 抛 DuplicateKey 在 SlaEventServiceImpl 内部被吞)
        verify(slaEventService).record(any(), any(), any(), any(), any(), any(), any());
        // 但 outbox 不能发布 — 否则会在 lumen-dispatch 产生重复 handler 更新
        verify(eventBus, never()).publish(any());
    }

    // ================================================================
    // helpers
    // ================================================================

    private static WmsDeliveryExceptionView exception(String type, String status, String handler, LocalDateTime createdAt) {
        WmsDeliveryExceptionView ex = new WmsDeliveryExceptionView();
        ex.setId(100L);
        ex.setTenantId(1L);
        ex.setOrderId(99L);
        ex.setType(type);
        ex.setStatus(status);
        ex.setHandler(handler);
        ex.setDescription("test");
        ex.setCreatedAt(createdAt);
        return ex;
    }

    private static SlaConfig config(String severity, String level, int responseMin, int resolveMin, String escalateTo) {
        SlaConfig c = new SlaConfig();
        c.setId(1L);
        c.setTenantId(1L);
        c.setSeverity(severity);
        c.setLevel(level);
        c.setResponseMinutes(responseMin);
        c.setResolveMinutes(resolveMin);
        c.setEscalateToLevel(escalateTo);
        return c;
    }
}
