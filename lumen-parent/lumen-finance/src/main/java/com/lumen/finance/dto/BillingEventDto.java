package com.lumen.finance.dto;

import java.util.Map;

/**
 * 计费事件入参 (Iteration 4.3.1, PR3-T3.2).
 *
 * <p>{@link com.lumen.finance.service.BillingEngineService#accrue(BillingEventDto)} 的入参,
 * 由 {@link com.lumen.finance.listener.BusinessEventListener} 把上游 outbox 事件
 * (DeliveryOrderStateChangedEvent / WmsInboundCompletedEvent / WmsOutboundCompletedEvent)
 * 映射成此 DTO 后传入.
 *
 * <p>字段含义:
 * <ul>
 *   <li>{@code tenantId} — 多租户隔离键 (从 DomainEvent 取)</li>
 *   <li>{@code sourceEventId} — outbox event id, 写 {@code t_billing_event_log.source_event_id},
 *       与 (tenant_id, deleted) 构成 UNIQUE 防重复 (幂等)</li>
 *   <li>{@code businessType} — LAST_MILE / WAREHOUSE_IN / WAREHOUSE_OUT</li>
 *   <li>{@code businessId} — 派车单 / 入库单 / 出库单 id</li>
 *   <li>{@code direction} — RECEIVABLE / PAYABLE</li>
 *   <li>{@code counterpartyId}, {@code counterpartyType} — 计费对手方</li>
 *   <li>{@code env} — Aviator 表达式求值环境 (weight / distance / amount / quantity 等)</li>
 * </ul>
 */
public record BillingEventDto(
    Long tenantId,
    String sourceEventId,
    String businessType,
    Long businessId,
    String direction,
    Long counterpartyId,
    String counterpartyType,
    Map<String, Object> env
) {
    /**
     * 防御性构造: env 为 null 时给空 Map, 避免下游 NPE.
     */
    public BillingEventDto {
        env = env == null ? Map.of() : env;
    }
}
