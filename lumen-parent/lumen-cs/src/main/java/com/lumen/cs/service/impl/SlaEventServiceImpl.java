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

/**
 * SLA 事件服务实现 (Iteration 4.2 PR4).
 *
 * <p><b>幂等写入:</b> 捕获 {@link DuplicateKeyException} 静默忽略 —
 * {@code UNIQUE KEY (exception_id, event_type, current_level, deleted)}
 * 保证同一工单同一升级点不会被并发重复写入. PR4 阶段典型场景:
 * 两个 scheduler 线程同时扫描同一异常都决定升级 → 第二个线程写失败,
 * 但 {@code eventBus.publish(...)} 仍会发出 (L1→L2 升级的 outbox 事件
 * 投递走 dispatcher 去重, 而非 t_sla_event UNIQUE KEY).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaEventServiceImpl implements SlaEventService {

    private static final String EVENT_TYPE_L1_RESPONDED = "L1_RESPONDED";

    private final SlaEventMapper mapper;

    @Override
    public SlaEventDto record(Long tenantId, Long exceptionId, String eventType,
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
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException dup) {
            // 幂等: UNIQUE KEY 命中说明已记录, 跳过. 实际写库失败由 unique 约束保证安全.
            log.debug("SlaEvent duplicate suppressed: tenant={} exception={} type={} level={}",
                tenantId, exceptionId, eventType, currentLevel);
        }
        return toDto(row);
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
