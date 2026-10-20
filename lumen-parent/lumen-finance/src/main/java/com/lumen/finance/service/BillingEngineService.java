package com.lumen.finance.service;

import com.lumen.finance.dto.BillingEventDto;
import com.lumen.finance.dto.EstimateResult;
import com.lumen.finance.entity.BillingAccrual;

import java.util.Map;

/**
 * 计费引擎服务接口 (Iteration 4.3.1, PR3-T3.2; 4.5B.1 PR1-T1.5 加 estimate).
 *
 * <p>试算主入口: 给定业务事件 DTO, 匹配规则 + Aviator 求值 + aggregate 写 accrual.
 * 由 {@link com.lumen.finance.listener.BusinessEventListener} 在
 * {@code @Async("outboxExecutor")} 线程里调用.
 *
 * <p>幂等保证:
 * <ul>
 *   <li>{@code t_billing_event_log} UNIQUE (tenant_id, source_event_id, deleted) — 重复事件直接返回 null</li>
 *   <li>{@code t_billing_accrual}  UNIQUE (tenant_id, source_event_id, business_type, deleted) — 重复 accrual 兜底</li>
 * </ul>
 *
 * <p>异常分类:
 * <ul>
 *   <li>无 active rule → log NO_RULE, return null (静默跳过)</li>
 *   <li>有 rule 但无 items → log NO_RULE, return null (静默跳过)</li>
 *   <li>有 rule + items 但求值失败 → log FAILED + 写 OPEN_FAILED accrual + 抛 {@code FinanceException(EXPRESSION_INVALID)}</li>
 *   <li>重复事件 (DuplicateKey) → 静默 return null</li>
 * </ul>
 */
public interface BillingEngineService {

    /**
     * 处理一条计费事件, 写 {@code t_billing_event_log} + 必要时写 {@code t_billing_accrual}.
     *
     * @param event 业务事件 DTO (见 {@link BillingEventDto})
     * @return 成功创建 accrual 时返回 accrual 实体; 重复 / NO_RULE 时返回 null;
     *         求值失败时返回 null 但已写 OPEN_FAILED accrual (异常由调用方决定如何处理)
     * @throws com.lumen.finance.exception.FinanceException 仅在求值失败时抛 EXPRESSION_INVALID,
     *         期望由 listener catch (outbox 重试链路接管)
     */
    BillingAccrual accrue(BillingEventDto event);

    /**
     * 同步估价 (不写库) — Iteration 4.5B.1.
     * <p>优先级: CustomerRateOverride (命中→返 base_amount) → BillingRule Aviator 求值 → miss 返 EstimateResult.none().
     * <p>用于 portal 端 preview 端点, 性能预算 < 200ms.
     *
     * @param tenantId     租户 ID
     * @param customerId   客户 ID
     * @param businessType 业务类型 (LAST_MILE / WAREHOUSING)
     * @param env          求值环境 (weight / distance / quantity / stopCount / totalWeightKg)
     * @return EstimateResult (amount / source / ruleId / overrideId / currency / breakdown)
     * @throws com.lumen.finance.exception.FinanceException 仅在 Aviator 求值失败时抛 EXPRESSION_INVALID
     */
    EstimateResult estimate(Long tenantId, Long customerId, String businessType, Map<String, Object> env);

    /**
     * 同步估价 + 写 accrual (Iteration 4.5B.1).
     * <p>用于 portal 端 createForCustomer 时调: 先 estimate 拿到结果 (供 DO 持久化时记录 sourceAmount),
     * 再走 accrue 写 OPEN accrual. 失败不抛 — DO 创建是关键路径, finance 失败由后台 job 重试.
     * <p>失败路径: 在 REQUIRES_NEW 事务里写 OPEN_FAILED accrual (与 accrue 内部的失败取证对称).
     *
     * @param event 业务事件 DTO (含 sourceEventId = doId, businessType = LAST_MILE,
     *              counterpartyId = customerId, env = {weight, distance, stopCount})
     */
    void estimateAndAccrue(BillingEventDto event);
}
