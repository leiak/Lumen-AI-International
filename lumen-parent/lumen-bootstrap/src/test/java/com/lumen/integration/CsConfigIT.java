package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.dto.CalendarDtos;
import com.lumen.cs.dto.HolidayDtos;
import com.lumen.cs.dto.SlaConfigDtos;
import com.lumen.cs.exception.CsException;
import com.lumen.cs.service.BusinessCalendarService;
import com.lumen.cs.service.HolidayService;
import com.lumen.cs.service.SlaConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CS 配置服务集成测试 (Iteration 4.2 PR2).
 *
 * <p>覆盖 4+ 个核心场景：
 * <ul>
 *   <li>SLA 配置 happy path（unique 重检测 → 409 业务错）</li>
 *   <li>业务时间 happy path（含校验失败）</li>
 *   <li>节假日 happy path（unique 重复检测）</li>
 *   <li>租户隔离 — 跨租户访问抛 NOT_FOUND</li>
 * </ul>
 *
 * <p>不写 HTTP 端点测试 — controller 层 @PreAuthorize 在 PR5 V25 引入
 * cs:* 权限码后才能端到端跑出 401/403。本类直接调 service 层（与
 * {@link DeliveryExceptionFlowIT} 一致），覆盖所有业务行为；HTTP / 权限
 * 层覆盖待 PR5 落地后追加。
 */
@DisplayName("CS 配置集成测试")
class CsConfigIT extends IntegrationBase {

    @Autowired private SlaConfigService slaConfigService;
    @Autowired private BusinessCalendarService businessCalendarService;
    @Autowired private HolidayService holidayService;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("slaConfig: create + duplicate → DUPLICATE_SLA_CONFIG")
    void slaConfig_createDuplicate() {
        SlaConfigDtos.CreateRequest req = new SlaConfigDtos.CreateRequest("HIGH", "L1", 30, 240, "L2");

        SlaConfigDtos.GetResponse first = slaConfigService.create(1L, req);
        assertThat(first.id()).isNotNull();
        assertThat(first.severity()).isEqualTo("HIGH");
        assertThat(first.level()).isEqualTo("L1");
        assertThat(first.escalateToLevel()).isEqualTo("L2");

        // Same (tenant, severity, level) → DUPLICATE_SLA_CONFIG (409)
        assertThatThrownBy(() -> slaConfigService.create(1L, req))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.DUPLICATE_SLA_CONFIG);
    }

    @Test
    @DisplayName("businessCalendar: weekday=6 start + endTime<=startTime → INVALID_WEEKDAY")
    void businessCalendar_invalidRangeThrows() {
        // endTime == startTime → service validation throws INVALID_WEEKDAY(range message)
        assertThatThrownBy(() -> businessCalendarService.create(1L,
            new CalendarDtos.CreateRequest(1, LocalTime.of(9, 0), LocalTime.of(9, 0))))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.INVALID_WEEKDAY);

        // weekday=8 → out of range
        assertThatThrownBy(() -> businessCalendarService.create(1L,
            new CalendarDtos.CreateRequest(8, LocalTime.of(9, 0), LocalTime.of(18, 0))))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.INVALID_WEEKDAY);
    }

    @Test
    @DisplayName("businessCalendar: create + list + delete happy path")
    void businessCalendar_happyPath() {
        CalendarDtos.GetResponse created = businessCalendarService.create(1L,
            new CalendarDtos.CreateRequest(2, LocalTime.of(10, 0), LocalTime.of(15, 0)));
        assertThat(created.weekday()).isEqualTo(2);
        assertThat(created.startTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(created.endTime()).isEqualTo(LocalTime.of(15, 0));

        var listed = businessCalendarService.list(1L);
        assertThat(listed.items()).extracting(CalendarDtos.GetResponse::weekday).contains(2);
        assertThat(listed.total()).isGreaterThanOrEqualTo(1);

        businessCalendarService.delete(1L, created.id());

        assertThatThrownBy(() -> businessCalendarService.get(1L, created.id()))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.BUSINESS_CALENDAR_NOT_FOUND);
    }

    @Test
    @DisplayName("holiday: create + duplicate + tenant isolation")
    void holiday_createDuplicateAndIsolation() {
        HolidayDtos.CreateRequest req = new HolidayDtos.CreateRequest(LocalDate.of(2026, 10, 1), "国庆节");

        HolidayDtos.GetResponse created = holidayService.create(1L, req);
        assertThat(created.holidayDate()).isEqualTo(LocalDate.of(2026, 10, 1));

        // Duplicate (tenant=1, date=2026-10-01) → DUPLICATE_HOLIDAY
        assertThatThrownBy(() -> holidayService.create(1L, req))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.DUPLICATE_HOLIDAY);

        // Same date, different tenant → ok (tenant isolation)
        TenantContext.set(2L);
        HolidayDtos.GetResponse t2 = holidayService.create(2L, req);
        assertThat(t2.id()).isNotEqualTo(created.id());
        TenantContext.set(1L);

        // Cross-tenant access (tenant=1 looking at id from tenant=2) → HOLIDAY_NOT_FOUND
        assertThatThrownBy(() -> holidayService.get(1L, t2.id()))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.HOLIDAY_NOT_FOUND);
    }

    @Test
    @DisplayName("slaConfig: list 跨租户隔离")
    void sla_configList_tenantIsolation() {
        // Tenant 1
        slaConfigService.create(1L,
            new SlaConfigDtos.CreateRequest("LOW", "L1", 480, 4320, "L2"));

        // Tenant 2: should NOT see tenant 1's configs
        var t1List = slaConfigService.list(1L,
            new SlaConfigDtos.ListRequest("LOW", "L1", 1, 50));
        var t2List = slaConfigService.list(2L,
            new SlaConfigDtos.ListRequest("LOW", "L1", 1, 50));
        assertThat(t1List.items()).extracting(SlaConfigDtos.GetResponse::tenantId).containsOnly(1L);
        assertThat(t2List.items()).isEmpty();
    }

    @Test
    @DisplayName("holiday: list + isHoliday 快速查询")
    void holiday_listAndIsHoliday() {
        LocalDate date = LocalDate.of(2026, 12, 25);
        holidayService.create(1L, new HolidayDtos.CreateRequest(date, "圣诞节"));

        assertThat(holidayService.isHoliday(1L, date)).isTrue();
        assertThat(holidayService.isHoliday(1L, LocalDate.of(2026, 12, 24))).isFalse();

        var listed = holidayService.list(1L);
        assertThat(listed.items()).extracting(HolidayDtos.GetResponse::holidayDate).contains(date);
    }
}