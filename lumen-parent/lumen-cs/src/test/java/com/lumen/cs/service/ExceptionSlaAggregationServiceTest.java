package com.lumen.cs.service;

import com.lumen.cs.calculator.SlaCalculator;
import com.lumen.cs.dto.CalendarDtos;
import com.lumen.cs.dto.EscalationDtos.ExceptionSlaView;
import com.lumen.cs.dto.EscalationDtos.SlaEventDto;
import com.lumen.cs.dto.HolidayDtos;
import com.lumen.cs.entity.SlaConfig;
import com.lumen.cs.entity.WmsDeliveryExceptionView;
import com.lumen.cs.mapper.SlaConfigMapper;
import com.lumen.cs.service.impl.ExceptionSlaAggregationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ExceptionSlaAggregationService 单元测试 (Iteration 4.2 PR6 finalize fix).
 *
 * <p>覆盖 CS 异常工单 → SLA 视角视图的转换:
 * <ul>
 *   <li>type → severity 派生 (DAMAGE→HIGH; OVERTIME/REJECTED→MEDIUM; ADDR_ERR→LOW)</li>
 *   <li>handler → level 推断 (cs_manager → L3; cs_supervisor → L2; 其他 → L1)</li>
 *   <li>severity → types 反向映射 (HIGH ↔ {DAMAGE}; MEDIUM ↔ {OVERTIME, REJECTED}; LOW ↔ {ADDR_ERR})</li>
 *   <li>{@code computeEscalationStatus} 阈值 (BREACHED ≥ 1.0; WARNING ≥ 0.8; OK < 0.8)</li>
 *   <li>{@code toView} 聚合一行字段 (currentLevel, severity, businessMinutesElapsed,
 *       resolveTargetMinutes, escalationStatus, slaEvents 列表)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExceptionSlaAggregationService 单元测试")
class ExceptionSlaAggregationServiceTest {

    @Mock private SlaConfigMapper slaConfigMapper;
    @Mock private SlaEventService slaEventService;
    @Mock private BusinessCalendarService businessCalendarService;
    @Mock private HolidayService holidayService;

    private final SlaCalculator calculator = new SlaCalculator();

    private ExceptionSlaAggregationService aggregator;

    private static final long TENANT = 1L;
    private static final long EXCEPTION_ID = 100L;

    @BeforeEach
    void setUp() {
        aggregator = new ExceptionSlaAggregationService(
            slaConfigMapper, slaEventService, businessCalendarService, holidayService, calculator);

        // 默认: Mon-Fri 9-18, 无节假日
        lenient().when(businessCalendarService.list(eq(TENANT), any())).thenReturn(
            new CalendarDtos.ListResponse(List.of(
                new CalendarDtos.GetResponse(1L, TENANT, 1, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), null, null),
                new CalendarDtos.GetResponse(2L, TENANT, 2, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), null, null),
                new CalendarDtos.GetResponse(3L, TENANT, 3, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), null, null),
                new CalendarDtos.GetResponse(4L, TENANT, 4, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), null, null),
                new CalendarDtos.GetResponse(5L, TENANT, 5, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), null, null)
            ), 5L));
        lenient().when(holidayService.list(eq(TENANT), any()))
            .thenReturn(new HolidayDtos.ListResponse(List.of(), 0L));
        // 默认: 没 L1_RESPONDED — 用 createdAt 作起点
        lenient().when(slaEventService.getLatestResponse(eq(TENANT), eq(EXCEPTION_ID))).thenReturn(null);
    }

    // =========================================================
    // type → severity 派生 (公开提取后用以供 filter 反向 map)
    // =========================================================

    @Test
    @DisplayName("deriveSeverity: DAMAGE → HIGH")
    void deriveSeverity_damage() {
        assertThat(ExceptionSlaAggregationService.deriveSeverity("DAMAGE")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("deriveSeverity: OVERTIME → MEDIUM")
    void deriveSeverity_overtime() {
        assertThat(ExceptionSlaAggregationService.deriveSeverity("OVERTIME")).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("deriveSeverity: REJECTED → MEDIUM")
    void deriveSeverity_rejected() {
        assertThat(ExceptionSlaAggregationService.deriveSeverity("REJECTED")).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("deriveSeverity: ADDR_ERR → LOW")
    void deriveSeverity_addrErr() {
        assertThat(ExceptionSlaAggregationService.deriveSeverity("ADDR_ERR")).isEqualTo("LOW");
    }

    @Test
    @DisplayName("deriveSeverity: 未知 type → 抛 IllegalArgumentException (供 controller 返 4xx)")
    void deriveSeverity_unknown() {
        assertThatThrownBy(() -> ExceptionSlaAggregationService.deriveSeverity("WEIRD_TYPE"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("deriveSeverity: null → 抛 IllegalArgumentException")
    void deriveSeverity_null() {
        assertThatThrownBy(() -> ExceptionSlaAggregationService.deriveSeverity(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================
    // severity → types 反向映射 (FE 过滤器翻译)
    // =========================================================

    @Test
    @DisplayName("typesForSeverity: HIGH → {DAMAGE}")
    void typesForSeverity_high() {
        assertThat(ExceptionSlaAggregationService.typesForSeverity("HIGH"))
            .containsExactly("DAMAGE");
    }

    @Test
    @DisplayName("typesForSeverity: MEDIUM → {OVERTIME, REJECTED}")
    void typesForSeverity_medium() {
        assertThat(ExceptionSlaAggregationService.typesForSeverity("MEDIUM"))
            .containsExactlyInAnyOrder("OVERTIME", "REJECTED");
    }

    @Test
    @DisplayName("typesForSeverity: LOW → {ADDR_ERR}")
    void typesForSeverity_low() {
        assertThat(ExceptionSlaAggregationService.typesForSeverity("LOW"))
            .containsExactly("ADDR_ERR");
    }

    @Test
    @DisplayName("typesForSeverity: 未知 severity → 空 (controller 翻译成全表为空)")
    void typesForSeverity_unknown() {
        assertThat(ExceptionSlaAggregationService.typesForSeverity("UNKNOWN")).isEmpty();
    }

    @Test
    @DisplayName("typesForSeverity: null → 空 (caller 防御)")
    void typesForSeverity_null() {
        assertThat(ExceptionSlaAggregationService.typesForSeverity(null)).isEmpty();
    }

    // =========================================================
    // handler → level 推断
    // =========================================================

    @Test
    @DisplayName("inferLevel: 含 cs_manager → L3")
    void inferLevel_manager() {
        assertThat(ExceptionSlaAggregationService.inferLevel("cs_manager_1")).isEqualTo("L3");
    }

    @Test
    @DisplayName("inferLevel: 含 cs_supervisor → L2")
    void inferLevel_supervisor() {
        assertThat(ExceptionSlaAggregationService.inferLevel("cs_supervisor_42")).isEqualTo("L2");
    }

    @Test
    @DisplayName("inferLevel: 普通 handler → L1")
    void inferLevel_normal() {
        assertThat(ExceptionSlaAggregationService.inferLevel("客服Alice")).isEqualTo("L1");
    }

    @Test
    @DisplayName("inferLevel: null handler → L1 (未分配, 刚创建)")
    void inferLevel_null() {
        assertThat(ExceptionSlaAggregationService.inferLevel(null)).isEqualTo("L1");
    }

    @Test
    @DisplayName("inferLevel: blank handler → L1")
    void inferLevel_blank() {
        assertThat(ExceptionSlaAggregationService.inferLevel("")).isEqualTo("L1");
    }

    // =========================================================
    // computeEscalationStatus 阈值 (与 FE computeEscalationStatus 一致)
    // =========================================================

    @Test
    @DisplayName("computeEscalationStatus: < 80% → OK")
    void status_underThreshold_ok() {
        assertThat(ExceptionSlaAggregationService.computeEscalationStatus(50, 100)).isEqualTo("OK");
        assertThat(ExceptionSlaAggregationService.computeEscalationStatus(79, 100)).isEqualTo("OK");
    }

    @Test
    @DisplayName("computeEscalationStatus: 80%~99% → WARNING")
    void status_warning() {
        assertThat(ExceptionSlaAggregationService.computeEscalationStatus(80, 100)).isEqualTo("WARNING");
        assertThat(ExceptionSlaAggregationService.computeEscalationStatus(99, 100)).isEqualTo("WARNING");
    }

    @Test
    @DisplayName("computeEscalationStatus: ≥ 100% → BREACHED")
    void status_breached() {
        assertThat(ExceptionSlaAggregationService.computeEscalationStatus(100, 100)).isEqualTo("BREACHED");
        assertThat(ExceptionSlaAggregationService.computeEscalationStatus(250, 100)).isEqualTo("BREACHED");
    }

    @Test
    @DisplayName("computeEscalationStatus: target<=0 → OK (防御: 没配 config 时不爆)")
    void status_zeroTarget() {
        assertThat(ExceptionSlaAggregationService.computeEscalationStatus(10, 0)).isEqualTo("OK");
        assertThat(ExceptionSlaAggregationService.computeEscalationStatus(10, -1)).isEqualTo("OK");
    }

    // =========================================================
    // toView - 整行聚合
    // =========================================================

    @Test
    @DisplayName("toView: 全字段填齐 (severity, currentLevel, elapsed, target, status, events)")
    void toView_fullRow() {
        // 用工作日 10:00 作为 createdAt — 保证 elapsed > 0 (避免在非工作时段跑测试时 0)
        LocalDateTime startTime = recentWeekdayMorning(2);
        WmsDeliveryExceptionView row = row("DAMAGE", "INVESTIGATING", "cs_supervisor_42",
            startTime);
        when(slaConfigMapper.selectOne(any())).thenReturn(
            config("HIGH", "L2", 30, 60, "L3"));
        when(slaEventService.listByException(eq(TENANT), eq(EXCEPTION_ID))).thenReturn(List.of(
            new SlaEventDto(11L, TENANT, EXCEPTION_ID, "L1_RESPONSE_DUE", "L1", "HIGH",
                startTime, null),
            new SlaEventDto(12L, TENANT, EXCEPTION_ID, "L1_RESPONDED", "L1", "HIGH",
                startTime.plusMinutes(30), null)
        ));

        ExceptionSlaView v = aggregator.toView(row);

        assertThat(v.exceptionId()).isEqualTo(EXCEPTION_ID);
        assertThat(v.type()).isEqualTo("DAMAGE");
        assertThat(v.status()).isEqualTo("INVESTIGATING");
        assertThat(v.handler()).isEqualTo("cs_supervisor_42");
        assertThat(v.currentLevel()).isEqualTo("L2");
        assertThat(v.severity()).isEqualTo("HIGH");
        assertThat(v.businessMinutesElapsed()).isGreaterThan(0L);
        assertThat(v.resolveTargetMinutes()).isEqualTo(60L);
        assertThat(v.escalationStatus()).isIn("OK", "WARNING", "BREACHED");
        assertThat(v.slaEvents()).hasSize(2);
    }

    @Test
    @DisplayName("toView: 配不到 config → resolveTargetMinutes=0, escalationStatus=OK (防御)")
    void toView_noConfig() {
        // createdAt 用工作日 10:00 前几天 — 避免 0 elapsed (mock config 也没关系, elapsed
        // 不参与 escalationStatus — 因为 target=0 时 computeEscalationStatus 直接 "OK")
        WmsDeliveryExceptionView row = row("ADDR_ERR", "OPEN", null,
            recentWeekdayMorning(3));
        when(slaConfigMapper.selectOne(any())).thenReturn(null);
        when(slaEventService.listByException(any(), any())).thenReturn(List.of());

        ExceptionSlaView v = aggregator.toView(row);

        assertThat(v.currentLevel()).isEqualTo("L1");
        assertThat(v.severity()).isEqualTo("LOW");
        assertThat(v.resolveTargetMinutes()).isZero();
        assertThat(v.escalationStatus()).isEqualTo("OK");
        assertThat(v.slaEvents()).isEmpty();
    }

    @Test
    @DisplayName("toView: 已有 L1_RESPONDED → 用响应时间作起点 (而不是 createdAt)")
    void toView_usesLatestResponseTime() {
        // 用工作日 10:00 作为 L1_RESPONDED 起点 — 保证 elapsed > 0 (避免非工作时段)
        LocalDateTime respondedAt = recentWeekdayMorning(1);
        WmsDeliveryExceptionView row = row("DAMAGE", "INVESTIGATING", "cs_manager_1",
            LocalDateTime.now().minusDays(7));
        when(slaConfigMapper.selectOne(any())).thenReturn(config("HIGH", "L3", 30, 1440, null));
        when(slaEventService.getLatestResponse(eq(TENANT), eq(EXCEPTION_ID))).thenReturn(
            new SlaEventDto(99L, TENANT, EXCEPTION_ID, "L1_RESPONDED", "L1", "HIGH", respondedAt, null));
        when(slaEventService.listByException(any(), any())).thenReturn(List.of());

        ExceptionSlaView v = aggregator.toView(row);

        // cs_manager → L3
        assertThat(v.currentLevel()).isEqualTo("L3");
        // 起点 = 昨天 10:00, 目标 = 1440 (24h) → 远小于阈值 → OK
        assertThat(v.businessMinutesElapsed()).isPositive();
        assertThat(v.escalationStatus()).isEqualTo("OK");
    }

    @Test
    @DisplayName("toView: 整个 elapsed 远超 target → escalationStatus=BREACHED")
    void toView_breached() {
        WmsDeliveryExceptionView row = row("DAMAGE", "OPEN", null,
            recentWeekdayMorning(7));
        when(slaConfigMapper.selectOne(any())).thenReturn(config("HIGH", "L1", 30, 240, "L2"));
        when(slaEventService.listByException(any(), any())).thenReturn(List.of());

        ExceptionSlaView v = aggregator.toView(row);

        assertThat(v.currentLevel()).isEqualTo("L1");
        assertThat(v.severity()).isEqualTo("HIGH");
        assertThat(v.resolveTargetMinutes()).isEqualTo(240L);
        assertThat(v.businessMinutesElapsed()).isGreaterThan(240L);
        assertThat(v.escalationStatus()).isEqualTo("BREACHED");
    }

    // =========================================================
    // SEVERITY_TO_TYPES map 自反验证
    // =========================================================

    @Test
    @DisplayName("typesForSeverity 与 deriveSeverity 自反: 所有 type 都映射到正确的 severity")
    void mappingIsReversible() {
        Set<String> types = Set.of("DAMAGE", "OVERTIME", "REJECTED", "ADDR_ERR");
        for (String t : types) {
            String sev = ExceptionSlaAggregationService.deriveSeverity(t);
            assertThat(ExceptionSlaAggregationService.typesForSeverity(sev))
                .as("type %s should reverse-map via severity %s", t, sev)
                .contains(t);
        }
    }

    // =========================================================
    // helpers
    // =========================================================

    /**
     * 找到「最近一个工作日上午 10:00」 — 保证 createdAt 在 Mon-Fri 9-18 区间内,
     * 否则 {@link com.lumen.cs.calculator.SlaCalculator} 算出 elapsed=0 (业务时间
     * 整体跳过). 跨周的回看循环: 7 天内必能找到工作日.
     */
    private static LocalDateTime recentWeekdayMorning(int daysBack) {
        for (int i = 1; i <= daysBack + 7; i++) {
            LocalDateTime candidate = LocalDateTime.now().minusDays(i)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
            java.time.DayOfWeek dow = candidate.getDayOfWeek();
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                return candidate;
            }
        }
        // 边角: 7 天 (实际不可能)
        return LocalDateTime.now().minusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private static WmsDeliveryExceptionView row(String type, String status, String handler, LocalDateTime createdAt) {
        WmsDeliveryExceptionView ex = new WmsDeliveryExceptionView();
        ex.setId(EXCEPTION_ID);
        ex.setTenantId(TENANT);
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
        c.setTenantId(TENANT);
        c.setSeverity(severity);
        c.setLevel(level);
        c.setResponseMinutes(responseMin);
        c.setResolveMinutes(resolveMin);
        c.setEscalateToLevel(escalateTo);
        return c;
    }
}
