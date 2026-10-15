package com.lumen.cs.service;

import com.lumen.cs.dto.EscalationDtos.SlaEventDto;
import com.lumen.cs.entity.SlaEvent;
import com.lumen.cs.mapper.SlaEventMapper;
import com.lumen.cs.service.impl.SlaEventServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SlaEventService 单元测试 (Iteration 4.2 PR4).
 *
 * <p>覆盖:
 * <ul>
 *   <li>record: 字段写入 + UNIQUE KEY 冲突时静默吞掉</li>
 *   <li>listByException: 按 occurred_at 升序</li>
 *   <li>getLatestResponse: 取最近 L1_RESPONDED; 没有返回 null</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SlaEventService 单元测试")
class SlaEventServiceTest {

    @Mock private SlaEventMapper mapper;
    private SlaEventServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SlaEventServiceImpl(mapper);
    }

    @Test
    @DisplayName("record: 字段写入 + 返回 dto (含 id) + version 设为 0")
    void record_persistsRow() {
        when(mapper.insert(any(SlaEvent.class))).thenAnswer(inv -> {
            SlaEvent arg = inv.getArgument(0);
            arg.setId(1L);
            return 1;
        });

        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 10, 0);
        Optional<SlaEventDto> result = service.record(1L, 100L, "ESCALATED", "L1", "HIGH",
            now, "{\"reason\":\"breach\"}");

        ArgumentCaptor<SlaEvent> cap = ArgumentCaptor.forClass(SlaEvent.class);
        verify(mapper).insert(cap.capture());
        SlaEvent row = cap.getValue();
        assertThat(row.getTenantId()).isEqualTo(1L);
        assertThat(row.getExceptionId()).isEqualTo(100L);
        assertThat(row.getEventType()).isEqualTo("ESCALATED");
        assertThat(row.getCurrentLevel()).isEqualTo("L1");
        assertThat(row.getSeverity()).isEqualTo("HIGH");
        assertThat(row.getOccurredAt()).isEqualTo(now);
        assertThat(row.getPayload()).isEqualTo("{\"reason\":\"breach\"}");
        // 防御性设置 version=0 (Important #3)
        assertThat(row.getVersion()).isEqualTo(0);

        assertThat(result).isPresent();
        SlaEventDto dto = result.get();
        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.tenantId()).isEqualTo(1L);
        assertThat(dto.eventType()).isEqualTo("ESCALATED");
    }

    @Test
    @DisplayName("record: UNIQUE KEY 冲突 → 返回 Optional.empty() (调用方跳过 outbox 副作用)")
    void record_duplicateKeyException_returnsEmpty() {
        when(mapper.insert(any(SlaEvent.class)))
            .thenThrow(new DuplicateKeyException("uk_exception_event_target"));

        LocalDateTime now = LocalDateTime.now();
        // 不抛异常 → 验证幂等性
        Optional<SlaEventDto> result = service.record(1L, 100L, "ESCALATED", "L1", "HIGH", now, null);

        // 命中 UNIQUE KEY → empty. 调用方据此跳过 outbox publish.
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listByException: 按 occurredAt 升序返回 DTO 列表")
    void listByException_returnsOrderedDtos() {
        SlaEvent e1 = eventRow(1L, 1L, 100L, "L1_RESPONSE_DUE", "L1", "HIGH",
            LocalDateTime.of(2026, 10, 15, 9, 0), null);
        SlaEvent e2 = eventRow(2L, 1L, 100L, "L1_RESPONDED",   "L1", "HIGH",
            LocalDateTime.of(2026, 10, 15, 10, 0), null);
        when(mapper.selectList(any())).thenReturn(List.of(e1, e2));

        List<SlaEventDto> result = service.listByException(1L, 100L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).eventType()).isEqualTo("L1_RESPONSE_DUE");
        assertThat(result.get(1).eventType()).isEqualTo("L1_RESPONDED");
    }

    @Test
    @DisplayName("getLatestResponse: 没响应事件 → null")
    void getLatestResponse_noRow_returnsNull() {
        when(mapper.selectOne(any())).thenReturn(null);

        SlaEventDto result = service.getLatestResponse(1L, 100L);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getLatestResponse: 找到最近 L1_RESPONDED → 返回 DTO")
    void getLatestResponse_returnsDto() {
        SlaEvent row = eventRow(5L, 1L, 100L, "L1_RESPONDED", "L1", "HIGH",
            LocalDateTime.of(2026, 10, 15, 11, 30), "{\"handler\":\"Alice\"}");
        when(mapper.selectOne(any())).thenReturn(row);

        SlaEventDto result = service.getLatestResponse(1L, 100L);
        assertThat(result).isNotNull();
        assertThat(result.eventType()).isEqualTo("L1_RESPONDED");
        assertThat(result.occurredAt()).isEqualTo(LocalDateTime.of(2026, 10, 15, 11, 30));
    }

    private static SlaEvent eventRow(long id, long tenantId, long exceptionId,
                                     String eventType, String level, String severity,
                                     LocalDateTime occurredAt, String payload) {
        SlaEvent e = new SlaEvent();
        e.setId(id);
        e.setTenantId(tenantId);
        e.setExceptionId(exceptionId);
        e.setEventType(eventType);
        e.setCurrentLevel(level);
        e.setSeverity(severity);
        e.setOccurredAt(occurredAt);
        e.setPayload(payload);
        return e;
    }
}
