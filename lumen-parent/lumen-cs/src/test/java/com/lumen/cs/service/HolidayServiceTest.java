package com.lumen.cs.service;

import com.lumen.cs.dto.HolidayDtos.CreateRequest;
import com.lumen.cs.entity.Holiday;
import com.lumen.cs.exception.CsErrorCode;
import com.lumen.cs.exception.CsException;
import com.lumen.cs.mapper.HolidayMapper;
import com.lumen.cs.service.impl.HolidayServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * HolidayService 单元测试 (Iteration 4.2 PR2).
 *
 * <p>覆盖：UNIQUE KEY 重复抛 DUPLICATE_HOLIDAY，跨租户隔离抛 HOLIDAY_NOT_FOUND.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HolidayService 单元测试")
class HolidayServiceTest {

    @Mock private HolidayMapper mapper;
    private HolidayServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new HolidayServiceImpl(mapper);
    }

    @Test
    @DisplayName("create: UNIQUE KEY 冲突 → DUPLICATE_HOLIDAY")
    void create_duplicate() {
        when(mapper.insert(any(Holiday.class)))
            .thenThrow(new DuplicateKeyException("uk_tenant_date"));

        assertThatThrownBy(() -> service.create(1L,
            new CreateRequest(LocalDate.of(2026, 10, 1), "国庆节")))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.DUPLICATE_HOLIDAY);
    }

    @Test
    @DisplayName("get: 跨租户 → HOLIDAY_NOT_FOUND")
    void get_tenantIsolation() {
        Holiday row = new Holiday();
        row.setId(10L);
        row.setTenantId(2L);
        row.setHolidayDate(LocalDate.of(2026, 10, 1));
        when(mapper.selectById(10L)).thenReturn(row);

        assertThatThrownBy(() -> service.get(1L, 10L))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.HOLIDAY_NOT_FOUND);
    }

    @Test
    @DisplayName("update: id 不存在 → HOLIDAY_NOT_FOUND")
    void update_notFound() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(1L, 99L,
            new com.lumen.cs.dto.HolidayDtos.UpdateRequest("foo")))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.HOLIDAY_NOT_FOUND);
    }
}