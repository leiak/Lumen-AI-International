package com.lumen.cs.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.cs.dto.EscalationDtos.SlaEventDto;
import com.lumen.cs.entity.SlaEvent;
import com.lumen.cs.mapper.SlaEventMapper;
import com.lumen.cs.service.SlaEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SLA 事件服务实现 (Iteration 4.2 PR4).
 *
 * <p><b>幂等写入:</b> 捕获 {@link DuplicateKeyException} 静默忽略 —
 * {@code UNIQUE KEY (exception_id, event_type, current_level, deleted)}
 * 保证同一工单同一升级点不会被并发重复写入.
 *
 * <p>返回 {@code Optional} 用以区分 "新插入" 与 "命中 UNIQUE KEY":
 * <ul>
 *   <li>{@code Optional.of(dto)} — 新插入, 调用方应执行副作用 (e.g. 发 outbox)</li>
 *   <li>{@code Optional.empty()} — 命中 UNIQUE KEY, 调用方应跳过副作用,
 *       否则 outbox 会出现重复行 (OutboxDispatcher 不按 eventId 去重).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaEventServiceImpl implements SlaEventService {

    private static final String EVENT_TYPE_L1_RESPONDED = "L1_RESPONDED";

    private final SlaEventMapper mapper;

    @Override
    public Optional<SlaEventDto> record(Long tenantId, Long exceptionId, String eventType,
                                        String currentLevel, String severity, LocalDateTime occurredAt,
                                        String payloadJson) {
        SlaEvent row = new SlaEvent();
        row.setTenantId(tenantId);
        row.setExceptionId(exceptionId);
        row.setEventType(eventType);
        row.setCurrentLevel(currentLevel);
        row.setSeverity(severity);
        row.setOccurredAt(occurredAt);
        row.setPayload(payloadJson);
        // 防御性设置 version = 0, 以防 {@code MetaObjectHandler} 未注入或
        // 未启用时 MyBatis-Plus 乐观锁字段为 null 触发 SQL 错误.
        row.setVersion(0);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException dup) {
            // 幂等: UNIQUE KEY 命中说明已记录, 跳过插入; 调用方应基于 empty() 跳过副作用.
            log.debug("SlaEvent duplicate suppressed: tenant={} exception={} type={} level={}",
                tenantId, exceptionId, eventType, currentLevel);
            return Optional.empty();
        }
        return Optional.of(toDto(row));
    }

    @Override
    public List<SlaEventDto> listByException(Long tenantId, Long exceptionId) {
        return mapper.selectList(new LambdaQueryWrapper<SlaEvent>()
                .eq(SlaEvent::getTenantId, tenantId)
                .eq(SlaEvent::getExceptionId, exceptionId)
                .orderByAsc(SlaEvent::getOccurredAt))
            .stream().map(this::toDto).toList();
    }

    @Override
    public SlaEventDto getLatestResponse(Long tenantId, Long exceptionId) {
        SlaEvent row = mapper.selectOne(new LambdaQueryWrapper<SlaEvent>()
                .eq(SlaEvent::getTenantId, tenantId)
                .eq(SlaEvent::getExceptionId, exceptionId)
                .eq(SlaEvent::getEventType, EVENT_TYPE_L1_RESPONDED)
                .orderByDesc(SlaEvent::getOccurredAt)
                .last("LIMIT 1"));
        return row == null ? null : toDto(row);
    }

    private SlaEventDto toDto(SlaEvent row) {
        return new SlaEventDto(
            row.getId(), row.getTenantId(), row.getExceptionId(), row.getEventType(),
            row.getCurrentLevel(), row.getSeverity(), row.getOccurredAt(), row.getPayload()
        );
    }
}
