package com.lumen.cs.service;

import com.lumen.cs.dto.CalendarDtos.CreateRequest;
import com.lumen.cs.entity.BusinessCalendar;
import com.lumen.cs.exception.CsErrorCode;
import com.lumen.cs.exception.CsException;
import com.lumen.cs.mapper.BusinessCalendarMapper;
import com.lumen.cs.service.impl.BusinessCalendarServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * BusinessCalendarService 单元测试 (Iteration 4.2 PR2).
 *
 * <p>weekday / endTime 校验无需 mock DB — service 在调 mapper 前就抛错.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BusinessCalendarService 单元测试")
class BusinessCalendarServiceTest {

    @Mock private BusinessCalendarMapper mapper;
    private BusinessCalendarServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BusinessCalendarServiceImpl(mapper);
    }

    @Test
    @DisplayName("create: weekday=0 → INVALID_WEEKDAY")
    void create_weekdayZero_throws() {
        assertThatThrownBy(() -> service.create(1L,
            new CreateRequest(0, LocalTime.of(9, 0), LocalTime.of(18, 0))))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.INVALID_WEEKDAY);
    }

    @Test
    @DisplayName("create: weekday=8 → INVALID_WEEKDAY")
    void create_weekday8_throws() {
        assertThatThrownBy(() -> service.create(1L,
            new CreateRequest(8, LocalTime.of(9, 0), LocalTime.of(18, 0))))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.INVALID_WEEKDAY);
    }

    @Test
    @DisplayName("create: endTime <= startTime → INVALID_TIME_RANGE")
    void create_endBeforeStart_throws() {
        assertThatThrownBy(() -> service.create(1L,
            new CreateRequest(1, LocalTime.of(18, 0), LocalTime.of(9, 0))))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.INVALID_TIME_RANGE);
    }

    @Test
    @DisplayName("update: id 不存在 → BUSINESS_CALENDAR_NOT_FOUND")
    void update_notFound() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(1L, 99L,
            new com.lumen.cs.dto.CalendarDtos.UpdateRequest(LocalTime.of(9, 0), LocalTime.of(18, 0))))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.BUSINESS_CALENDAR_NOT_FOUND);
    }

    @Test
    @DisplayName("get: 跨租户 → BUSINESS_CALENDAR_NOT_FOUND")
    void get_tenantIsolation() {
        BusinessCalendar row = new BusinessCalendar();
        row.setId(50L);
        row.setTenantId(2L);
        when(mapper.selectById(50L)).thenReturn(row);

        assertThatThrownBy(() -> service.get(1L, 50L))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.BUSINESS_CALENDAR_NOT_FOUND);
    }
}