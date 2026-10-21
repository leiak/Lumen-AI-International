package com.lumen.finance.service;

import com.lumen.finance.dto.BillingEventDto;
import com.lumen.finance.dto.EstimateResult;
import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.exception.FinanceErrorCode;

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

    /**
     * 4.5C-1 — 纯读预检: 该 sourceEventId 的应计当前是否可冲销.
     *
     * <p>不修改任何数据。供 {@code cancelForCustomer} 在改 DO 状态<b>之前</b>调用,
     * 让被拒绝的取消不会留下"DO 已 CANCELLED 但账上仍有应计"的半成品单。
     *
     * <p>查不到 / OPEN / OPEN_FAILED / RELEASED 一律放行。
     * 已 CONVERTED / LOCKED 抛
     * {@link FinanceErrorCode#ACCRUAL_ALREADY_CONVERTED} (409_004)。
     *
     * <p>只查 {@code business_type = LAST_MILE} 的应计（门户取消路径的既有范围）;
     * 其它 business_type 查不到即视为放行。
     *
     * @param tenantId     租户 ID
     * @param sourceEventId 业务源 ID — 门户路径下即 {@code String.valueOf(doId)}
     */
    void assertReversible(Long tenantId, String sourceEventId);

    /**
     * 4.5C-1 — 按 sourceEventId 冲销一笔应计 (门户取消订单时调用).
     *
     * <p>幂等: 查不到 / 已 RELEASED / {@code tenantId|sourceEventId} 任一为 null 一律视为成功。
     * 已 CONVERTED / LOCKED 抛 {@link FinanceErrorCode#ACCRUAL_ALREADY_CONVERTED} (409_004).
     *
     * <p><b>范围</b>: 只匹配 {@code business_type = LAST_MILE} 的应计 (与
     * {@link #assertReversible} 同范围)。传入别的 business_type 会走「查不到即视为成功」
     * 这条分支 —— 静默不动账, 正是 4.5C-1 要消灭的那类账实不符。接新业务类型前
     * 必须先扩 {@code findBySourceEventId} 的过滤条件。
     *
     * <p>真正的释放在 REQUIRES_NEW 独立事务里跑 ——
     * {@code AccrualService.release} 自身标 {@code @Transactional} (REQUIRED 传播),
     * 直接调会加入调用方 (cancelForCustomer) 的事务; 一旦 release 抛异常, 默认配置下
     * Spring 会把外层事务标 rollback-only, dispatch 侧 catch 掉继续走的话会在 commit
     * 时抛 {@code UnexpectedRollbackException} —— "冲销失败不阻塞取消" 就变成了
     * "整单取消失败". 独立事务里失败只回滚自己.
     *
     * <p>用 {@code TransactionTemplate.executeWithoutResult} 而非
     * {@code @Transactional(REQUIRES_NEW)}: 同 bean 自调用会绕过 Spring 代理, 注解形同虚设。
     * 这一处写法的干净样板见 {@code BillingRuleServiceImpl.commitInvalidAndAudit}
     * (注: {@code estimateAndAccrue → accrue} 是 4.5B 既有的同 bean 自调用问题,
     * 不在本方法范围, 留着未来迭代处理).
     *
     * <p><b>代价</b>: 冲销与 DO 状态变更<b>不原子</b> —— 冲销先独立提交, 若外层随后回滚,
     * 结果是 "应计已 RELEASED 而订单仍在 PENDING_DISPATCH" (在飞订单被少计费)。
     * 这个方向可观测可补, 反向 (虚增收入) 不可逆, 故取此取舍.
     *
     * <p><b>返回值仅供日志/审计, 调用方无需判断</b> — 冲销成功与否完全由「是否抛异常」
     * 表达。dispatch 侧 (cancelForCustomer) 只需 try/catch, 不要 switch outcome.
     *
     * @param tenantId      租户 ID
     * @param sourceEventId 业务源 ID — 门户路径下即 {@code String.valueOf(doId)}
     * @param reason        写入 accrual.error_msg 留痕
     */
    BillingReversalResult reversalForSourceEventId(Long tenantId, String sourceEventId, String reason);

    /**
     * 4.5C-1 — 冲销结果.
     *
     * @param outcome  取 {@link #OUTCOME_RELEASED} / {@link #OUTCOME_ALREADY_RELEASED}
     *                 / {@link #OUTCOME_NOT_FOUND} 之一
     * @param accrualId 命中的 accrual 主键; {@link #OUTCOME_NOT_FOUND} 时为 null
     */
    record BillingReversalResult(String outcome, Long accrualId) {
        /** 本次释放 (本次调用真的调了 release, 应计从 OPEN/OPEN_FAILED 转 RELEASED). */
        public static final String OUTCOME_RELEASED = "RELEASED";
        /** 已释放 (幂等跳过 — 历史 cancel 已经处理过, 本次不再动). */
        public static final String OUTCOME_ALREADY_RELEASED = "ALREADY_RELEASED";
        /** 无应计 (查询不到 — createForCustomer 有 "不写 accrual" 路径 / 入参为 null). */
        public static final String OUTCOME_NOT_FOUND = "NOT_FOUND";
    }
}
