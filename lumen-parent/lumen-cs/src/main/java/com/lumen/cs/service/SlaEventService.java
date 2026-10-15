package com.lumen.cs.service;

import com.lumen.cs.dto.EscalationDtos.SlaEventDto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SLA 事件服务 (Iteration 4.2 PR4).
 *
 * <p>对应 {@code t_sla_event}: 每次升级 / 响应 / 解决都插入一行,
 * 用于客服台查询和审计追溯.
 *
 * <p><b>幂等性:</b> {@link #record} 依赖
 * {@code UNIQUE KEY (exception_id, event_type, current_level, deleted)}
 * 防并发重复 — 并发扫描两次同一工单, 第二次会命中 UniqueKey 并被静默忽略.
 */
public interface SlaEventService {

    /**
     * 写入一条 SLA 事件. 命中 UNIQUE KEY 时静默返回已存在行的 DTO.
     *
     * @param payloadJson 可选 JSON 字符串 (业务分钟数 / 升级原因 / 关联 event id 等).
     *                    传 {@code null} 表示不写 payload.
     */
    SlaEventDto record(Long tenantId, Long exceptionId, String eventType,
                       String currentLevel, String severity, LocalDateTime occurredAt,
                       String payloadJson);

    /** 按 exception 列出全部 SLA 事件 (occurred_at 升序). */
    List<SlaEventDto> listByException(Long tenantId, Long exceptionId);

    /** 获取该 exception 最新一次 L1_RESPONDED 事件; 没有则返回 {@code null}. */
    SlaEventDto getLatestResponse(Long tenantId, Long exceptionId);
}
