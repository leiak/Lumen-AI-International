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
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * Counter-based unique tenantId generator. 起始 100000L 避开真实业务租户；
     * 每个测试拿一个独占的 tenantId，避免跨测试共享行带来的 order-dependent 失败.
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(100_000L);
    private long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = TENANT_SEQ.incrementAndGet();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("slaConfig: create + duplicate → DUPLICATE_SLA_CONFIG")
    void slaConfig_createDuplicate() {
        SlaConfigDtos.CreateRequest req = new SlaConfigDtos.CreateRequest("HIGH", "L1", 30, 240, "L2");

        SlaConfigDtos.GetResponse first = slaConfigService.create(tenantId, req);
        assertThat(first.id()).isNotNull();
        assertThat(first.severity()).isEqualTo("HIGH");
        assertThat(first.level()).isEqualTo("L1");
        assertThat(first.escalateToLevel()).isEqualTo("L2");

        // Same (tenant, severity, level) → DUPLICATE_SLA_CONFIG (409)
        assertThatThrownBy(() -> slaConfigService.create(tenantId, req))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.DUPLICATE_SLA_CONFIG);
    }

    @Test
    @DisplayName("businessCalendar: endTime<=startTime → INVALID_TIME_RANGE; weekday=8 → INVALID_WEEKDAY")
    void businessCalendar_invalidRangeThrows() {
        // endTime == startTime → service validation throws INVALID_TIME_RANGE
        assertThatThrownBy(() -> businessCalendarService.create(tenantId,
            new CalendarDtos.CreateRequest(1, LocalTime.of(9, 0), LocalTime.of(9, 0))))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.INVALID_TIME_RANGE);

        // weekday=8 → out of range
        assertThatThrownBy(() -> businessCalendarService.create(tenantId,
            new CalendarDtos.CreateRequest(8, LocalTime.of(9, 0), LocalTime.of(18, 0))))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.INVALID_WEEKDAY);
    }

    @Test
    @DisplayName("businessCalendar: create + list + delete happy path")
    void businessCalendar_happyPath() {
        CalendarDtos.GetResponse created = businessCalendarService.create(tenantId,
            new CalendarDtos.CreateRequest(2, LocalTime.of(10, 0), LocalTime.of(15, 0)));
        assertThat(created.weekday()).isEqualTo(2);
        assertThat(created.startTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(created.endTime()).isEqualTo(LocalTime.of(15, 0));

        var listed = businessCalendarService.list(tenantId,
            new CalendarDtos.ListRequest(1, 200));
        assertThat(listed.items()).extracting(CalendarDtos.GetResponse::weekday).contains(2);
        assertThat(listed.total()).isGreaterThanOrEqualTo(1);

        businessCalendarService.delete(tenantId, created.id());

        assertThatThrownBy(() -> businessCalendarService.get(tenantId, created.id()))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.BUSINESS_CALENDAR_NOT_FOUND);
    }

    @Test
    @DisplayName("holiday: create + duplicate + tenant isolation")
    void holiday_createDuplicateAndIsolation() {
        HolidayDtos.CreateRequest req = new HolidayDtos.CreateRequest(LocalDate.of(2026, 10, 1), "国庆节");

        HolidayDtos.GetResponse created = holidayService.create(tenantId, req);
        assertThat(created.holidayDate()).isEqualTo(LocalDate.of(2026, 10, 1));

        // Duplicate (same tenant, same date) → DUPLICATE_HOLIDAY
        assertThatThrownBy(() -> holidayService.create(tenantId, req))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.DUPLICATE_HOLIDAY);

        // Same date, different tenant → ok (tenant isolation)
        long otherTenant = TENANT_SEQ.incrementAndGet();
        TenantContext.set(otherTenant);
        HolidayDtos.GetResponse t2 = holidayService.create(otherTenant, req);
        assertThat(t2.id()).isNotEqualTo(created.id());
        TenantContext.set(tenantId);

        // Cross-tenant access (tenantId looking at id from otherTenant) → HOLIDAY_NOT_FOUND
        assertThatThrownBy(() -> holidayService.get(tenantId, t2.id()))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(com.lumen.cs.exception.CsErrorCode.HOLIDAY_NOT_FOUND);
    }

    @Test
    @DisplayName("slaConfig: list 跨租户隔离")
    void sla_configList_tenantIsolation() {
        // Current tenant — owns 1 config
        slaConfigService.create(tenantId,
            new SlaConfigDtos.CreateRequest("LOW", "L1", 480, 4320, "L2"));

        // Other tenant: should NOT see current tenant's configs
        long otherTenant = TENANT_SEQ.incrementAndGet();
        var tList = slaConfigService.list(tenantId,
            new SlaConfigDtos.ListRequest("LOW", "L1", 1, 50));
        var otherList = slaConfigService.list(otherTenant,
            new SlaConfigDtos.ListRequest("LOW", "L1", 1, 50));
        assertThat(tList.items()).extracting(SlaConfigDtos.GetResponse::tenantId).containsOnly(tenantId);
        assertThat(otherList.items()).isEmpty();
    }

    @Test
    @DisplayName("holiday: list + isHoliday 快速查询")
    void holiday_listAndIsHoliday() {
        LocalDate date = LocalDate.of(2026, 12, 25);
        holidayService.create(tenantId, new HolidayDtos.CreateRequest(date, "圣诞节"));

        assertThat(holidayService.isHoliday(tenantId, date)).isTrue();
        assertThat(holidayService.isHoliday(tenantId, LocalDate.of(2026, 12, 24))).isFalse();

        var listed = holidayService.list(tenantId,
            new HolidayDtos.ListRequest(1, 200));
        assertThat(listed.items()).extracting(HolidayDtos.GetResponse::holidayDate).contains(date);
    }
}