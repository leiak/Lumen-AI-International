package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingEventDto;
import com.lumen.finance.dto.EstimateResult;
import com.lumen.finance.engine.BillingScriptEngine;
import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.entity.BillingEventLog;
import com.lumen.finance.entity.BillingRule;
import com.lumen.finance.entity.BillingRuleItem;
import com.lumen.finance.entity.CustomerRateOverride;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingAccrualMapper;
import com.lumen.finance.mapper.BillingEventLogMapper;
import com.lumen.finance.mapper.BillingRuleItemMapper;
import com.lumen.finance.mapper.BillingRuleMapper;
import com.lumen.finance.service.AccrualService;
import com.lumen.finance.service.BillingEngineService;
import com.lumen.finance.service.CustomerRateOverrideService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BillingEngineService 实现 (Iteration 4.3.1, PR3-T3.2).
 *
 * <p>核心流水线:
 * <ol>
 *   <li>插入 {@code t_billing_event_log} (RECEIVED) — UNIQUE 幂等; 重复事件返回 null</li>
 *   <li>查 ACTIVE rule (priority asc, effective 日期包含今天) — 缺则 NO_RULE + return</li>
 *   <li>查 rule items (sequence asc) — 缺则 NO_RULE + return</li>
 *   <li>逐条求值 → 累加 gross — 异常时 FAILED + 写 OPEN_FAILED accrual + 抛 EXPRESSION_INVALID</li>
 *   <li>写 {@code t_billing_accrual} (status=OPEN) — UNIQUE 兜底</li>
 *   <li>更新 log 状态 = SUCCESS + processedAt</li>
 * </ol>
 *
 * <p>所有方法都通过 {@link com.lumen.finance.entity.BillingEventLog#setTenantId} 显式传 tenant,
 * 配合 {@code MybatisPlusConfig} 的 {@code TenantLineInnerInterceptor} 拦截 SQL.
 */
@Slf4j
@Service
public class BillingEngineServiceImpl implements BillingEngineService {

    private final BillingEventLogMapper eventLogMapper;
    private final BillingRuleMapper ruleMapper;
    private final BillingRuleItemMapper itemMapper;
    private final BillingAccrualMapper accrualMapper;
    private final BillingScriptEngine scriptEngine;
    private final ObjectMapper objectMapper;
    /**
     * 仅用于 accrue 失败路径写 (event_log=FAILED + accrual=OPEN_FAILED) 的 REQUIRES_NEW 事务.
     * 这样抛出 FinanceException(EXPRESSION_INVALID) 触发外层事务回滚时, 失败标记已经落了库 —
     * 失败取证留痕, outbox 兜底重试的回路也清晰可见.
     */
    private final TransactionTemplate requiresNewTx;

    /** 4.5B.1 — estimate 路径上做高优先级覆盖判断. */
    private final CustomerRateOverrideService overrideService;

    /** 4.5C-1 — 冲销时复用它的 release, 不在本类重写一遍状态机校验。 */
    private final AccrualService accrualService;

    public BillingEngineServiceImpl(BillingEventLogMapper eventLogMapper,
                                    BillingRuleMapper ruleMapper,
                                    BillingRuleItemMapper itemMapper,
                                    BillingAccrualMapper accrualMapper,
                                    BillingScriptEngine scriptEngine,
                                    ObjectMapper objectMapper,
                                    PlatformTransactionManager txManager,
                                    CustomerRateOverrideService overrideService,
                                    AccrualService accrualService) {
        this.eventLogMapper = eventLogMapper;
        this.ruleMapper = ruleMapper;
        this.itemMapper = itemMapper;
        this.accrualMapper = accrualMapper;
        this.scriptEngine = scriptEngine;
        this.objectMapper = objectMapper;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.overrideService = overrideService;
        this.accrualService = accrualService;
    }

    @Override
    @Transactional
    public BillingAccrual accrue(BillingEventDto event) {
        if (event == null || event.tenantId() == null || event.sourceEventId() == null) {
            log.warn("accrue: invalid event payload (null tenantId/sourceEventId)");
            return null;
        }

        // 1. 写 event_log (RECEIVED) — UNIQUE (tenant_id, source_event_id, deleted) 兜底幂等
        BillingEventLog logRow = new BillingEventLog();
        logRow.setTenantId(event.tenantId());
        logRow.setSourceEventId(event.sourceEventId());
        logRow.setBusinessType(event.businessType());
        logRow.setBusinessId(event.businessId());
        logRow.setProcessStatus(FinanceConstants.EVT_STATUS_RECEIVED);
        // receivedAt 在 BillingEventLog 上是 FieldFill.INSERT, 但 MyMetaObjectHandler 当前
        // 不识别 received_at (只识别 created_at/updated_at/deleted/version/tenantId);
        // 为避免扩展 lumen-common 的基础设施, 直接显式填充.
        logRow.setReceivedAt(LocalDateTime.now());
        try {
            eventLogMapper.insert(logRow);
        } catch (DuplicateKeyException dup) {
            log.info("accrue: duplicate event (tenant={}, sourceEventId={}), skip",
                event.tenantId(), event.sourceEventId());
            return null;
        }

        // 2. 匹配 ACTIVE rule (priority asc, effective 日期包含 today)
        LocalDate today = LocalDate.now();
        LambdaQueryWrapper<BillingRule> ruleQ = new LambdaQueryWrapper<BillingRule>()
            .eq(BillingRule::getTenantId, event.tenantId())
            .eq(BillingRule::getBusinessType, event.businessType())
            .eq(BillingRule::getDirection, event.direction())
            .eq(BillingRule::getStatus, FinanceConstants.RULE_STATUS_ACTIVE)
            .le(BillingRule::getEffectiveFrom, today)
            .and(w -> w.isNull(BillingRule::getEffectiveTo)
                .or().ge(BillingRule::getEffectiveTo, today))
            .orderByAsc(BillingRule::getPriority)
            .orderByAsc(BillingRule::getId)
            .last("LIMIT 1");

        BillingRule rule = ruleMapper.selectOne(ruleQ);
        if (rule == null) {
            markNoRule(logRow, "No ACTIVE rule matched for (biz=" + event.businessType()
                + ", dir=" + event.direction() + ", today=" + today + ")");
            return null;
        }

        // 3. 查 items (sequence asc)
        List<BillingRuleItem> items = itemMapper.selectList(
            new LambdaQueryWrapper<BillingRuleItem>()
                .eq(BillingRuleItem::getRuleId, rule.getId())
                .orderByAsc(BillingRuleItem::getSequence)
        );
        if (items.isEmpty()) {
            markNoRule(logRow, "Rule " + rule.getId() + " has no items");
            return null;
        }

        // 4. 逐条求值, 累加 gross
        BigDecimal gross = BigDecimal.ZERO;
        for (BillingRuleItem item : items) {
            try {
                BigDecimal partial = scriptEngine.eval(item.getExpression(), event.env());
                gross = gross.add(partial == null ? BigDecimal.ZERO : partial);
            } catch (Exception ex) {
                String err = truncate("Item " + item.getSequence() + " eval failed: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()), 500);
                log.warn("accrue: rule {} item {} eval failed (tenant={}, sourceEventId={}): {}",
                    rule.getId(), item.getSequence(), event.tenantId(), event.sourceEventId(), err);

                // 失败路径: 在 REQUIRES_NEW 独立事务里写 (log=FAILED + accrual=OPEN_FAILED),
                // 这样后续 throw FinanceException(EXPRESSION_INVALID) 触发外层事务回滚时,
                // 失败标记已经落了库 — 失败取证留痕, outbox 兜底重试回路清晰可见.
                requiresNewTx.executeWithoutResult(status ->
                    writeFailureAudit(logRow, event, rule.getId(), err)
                );

                // 抛 FinanceException(EXPRESSION_INVALID), 由 listener catch
                throw new FinanceException(FinanceErrorCode.EXPRESSION_INVALID, err);
            }
        }

        // 5. 写 accrual (OPEN)
        BillingAccrual accrual = new BillingAccrual();
        accrual.setTenantId(event.tenantId());
        accrual.setSourceEventId(event.sourceEventId());
        accrual.setBusinessType(event.businessType());
        accrual.setBusinessId(event.businessId());
        accrual.setRuleId(rule.getId());
        accrual.setDirection(event.direction());
        accrual.setCounterpartyId(event.counterpartyId());
        accrual.setGrossAmount(gross);
        accrual.setTaxAmount(BigDecimal.ZERO); // 4.3.2 placeholder
        accrual.setNetAmount(gross);
        accrual.setCurrency(rule.getCurrency() == null ? FinanceConstants.CURRENCY_CNY : rule.getCurrency());
        accrual.setStatus(FinanceConstants.ACCRUAL_STATUS_OPEN);
        accrual.setPayloadJson(toJson(event.env()));
        try {
            accrualMapper.insert(accrual);
        } catch (DuplicateKeyException dup) {
            // 兜底: event_log 已被并发删除/重放, 但 accrual UNIQUE 拦截, 不抛给 listener
            log.info("accrue: accrual UNIQUE hit (tenant={}, sourceEventId={}, biz={}), skip",
                event.tenantId(), event.sourceEventId(), event.businessType());
            return null;
        }

        // 6. 更新 log 状态 = SUCCESS
        logRow.setProcessStatus(FinanceConstants.EVT_STATUS_SUCCESS);
        logRow.setProcessedAt(LocalDateTime.now());
        eventLogMapper.updateById(logRow);

        log.info("accrue: tenant={} sourceEventId={} biz={} rule={} gross={}",
            event.tenantId(), event.sourceEventId(), event.businessType(), rule.getId(), gross);
        return accrual;
    }

    /**
     * 同步估价 — Iteration 4.5B.1, PR1-T1.5.
     *
     * <p>优先级: {@code CustomerRateOverride} (命中→返 base_amount) → BillingRule Aviator 求值 → miss 返 {@link EstimateResult#none()}.
     * <p>不写任何表 (event_log / accrual 都不动); 失败仅在 Aviator 求值失败时抛
     * {@link FinanceException#(EXPRESSION_INVALID)}.
     */
    @Override
    public EstimateResult estimate(Long tenantId, Long customerId, String businessType, Map<String, Object> env) {
        // 1. override 优先 (currency 在 override 表 NOT NULL, 但与 rule 路径保持归一一致 — 缺省回退 CNY)
        Optional<CustomerRateOverride> overrideOpt = overrideService.findActive(
            tenantId, customerId, businessType, LocalDate.now());
        if (overrideOpt.isPresent()) {
            CustomerRateOverride o = overrideOpt.get();
            String currency = o.getCurrency() == null ? FinanceConstants.CURRENCY_CNY : o.getCurrency();
            String breakdown = String.format("合同价 %s %s (override #%d)",
                currency, o.getBaseAmount().toPlainString(), o.getId());
            return new EstimateResult(o.getBaseAmount(), EstimateResult.SOURCE_OVERRIDE,
                null, o.getId(), currency, breakdown);
        }

        // 2. 匹配 ACTIVE rule
        LambdaQueryWrapper<BillingRule> ruleQ = new LambdaQueryWrapper<BillingRule>()
            .eq(BillingRule::getTenantId, tenantId)
            .eq(BillingRule::getBusinessType, businessType)
            .eq(BillingRule::getStatus, FinanceConstants.RULE_STATUS_ACTIVE)
            .le(BillingRule::getEffectiveFrom, LocalDate.now())
            .and(w -> w.isNull(BillingRule::getEffectiveTo)
                .or().ge(BillingRule::getEffectiveTo, LocalDate.now()))
            .orderByAsc(BillingRule::getPriority)
            .orderByAsc(BillingRule::getId)
            .last("LIMIT 1");

        BillingRule rule = ruleMapper.selectOne(ruleQ);
        if (rule == null) {
            return EstimateResult.none();
        }

        List<BillingRuleItem> items = itemMapper.selectList(
            new LambdaQueryWrapper<BillingRuleItem>()
                .eq(BillingRuleItem::getRuleId, rule.getId())
                .orderByAsc(BillingRuleItem::getSequence));

        if (items.isEmpty()) {
            return EstimateResult.none();
        }

        // 3. 逐条求值, 任意一条失败 → FinanceException
        BigDecimal gross = BigDecimal.ZERO;
        StringBuilder breakdown = new StringBuilder();
        for (BillingRuleItem item : items) {
            BigDecimal partial;
            try {
                partial = scriptEngine.eval(item.getExpression(), env);
            } catch (Exception ex) {
                // 与 accrue 路径一致: 异常 message 为 null 时回退到 simpleName
                String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                throw new FinanceException(FinanceErrorCode.EXPRESSION_INVALID,
                    "estimate: item " + item.getSequence() + " eval failed: " + msg);
            }
            partial = partial == null ? BigDecimal.ZERO : partial;
            gross = gross.add(partial);
            if (breakdown.length() > 0) breakdown.append(" + ");
            breakdown.append(item.getExpression()).append("=").append(partial.toPlainString());
        }
        String currency = rule.getCurrency() == null ? FinanceConstants.CURRENCY_CNY : rule.getCurrency();
        return new EstimateResult(gross, EstimateResult.SOURCE_RULE,
            rule.getId(), null, currency, breakdown + " = " + gross.toPlainString());
    }

    /**
     * 同步估价 + 写 accrual (Iteration 4.5B.1, PR1-T1.6).
     * <p>用于 portal 端 createForCustomer 时调: 先 estimate 拿到结果 (供 DO 持久化时记录 sourceAmount),
     * 再走 accrue 写 OPEN accrual. <b>失败不抛</b> — DO 创建是关键路径, finance 失败由后台 job 重试.
     */
    @Override
    public void estimateAndAccrue(BillingEventDto event) {
        if (event == null || event.tenantId() == null || event.sourceEventId() == null) {
            log.warn("estimateAndAccrue: invalid event payload (null tenantId/sourceEventId)");
            return;
        }
        try {
            // 1. 同步估价 (供调用方记录 sourceAmount; 这里只用于判断 NONE 提前返回)
            EstimateResult result = estimate(event.tenantId(), event.counterpartyId(),
                event.businessType(), event.env());

            // 2. SOURCE_NONE 时跳过 accrue — accrual UNIQUE (tenant, sourceEventId, businessType) 仍需占位?
            //    当前决策: NONE 时直接 return, 不写 accrual. 由后台 reconciler 周期扫 DO 缺 accrual 补.
            if (EstimateResult.SOURCE_NONE.equals(result.source())) {
                log.info("estimateAndAccrue: NO_RULE (tenant={}, sourceEventId={}), skip accrual",
                    event.tenantId(), event.sourceEventId());
                return;
            }

            // 3. 调 accrue 写 OPEN accrual — accrue 内部已处理 override / rule / 求值 / accrual 落地 + 失败取证
            BillingAccrual accrual = accrue(event);
            if (accrual == null) {
                log.info("estimateAndAccrue: accrue returned null (duplicate or NO_RULE) for sourceEventId={}",
                    event.sourceEventId());
            }
        } catch (FinanceException ex) {
            // Aviator 失败 / 规则错误: estimate 路径抛出时 accrue 还没跑, 必须写 OPEN_FAILED 留痕;
            // 若 accrue 路径抛出, 它内部 writeFailureAudit 已写过, 这里调 writeEstimateAndAccrueFailure
            // 会撞 accrual UNIQUE → DuplicateKeyException 兜底 — 安全幂等.
            log.warn("estimateAndAccrue FinanceException for sourceEventId={}: {}",
                event.sourceEventId(), ex.getMessage());
            requiresNewTx.executeWithoutResult(status ->
                writeEstimateAndAccrueFailure(event, ex.getMessage())
            );
        } catch (Exception ex) {
            // 兜底: 任何未预期异常 (DB down / OOM 等) 也不抛, 写 OPEN_FAILED 留痕
            log.warn("estimateAndAccrue unexpected error for sourceEventId={}",
                event.sourceEventId(), ex);
            requiresNewTx.executeWithoutResult(status ->
                writeEstimateAndAccrueFailure(event, ex.getMessage())
            );
        }
    }

    // ----------------- helpers -----------------

    private void markNoRule(BillingEventLog row, String msg) {
        row.setProcessStatus(FinanceConstants.EVT_STATUS_NO_RULE);
        row.setErrorMsg(truncate(msg, 500));
        row.setProcessedAt(LocalDateTime.now());
        eventLogMapper.updateById(row);
        log.info("accrue: NO_RULE (id={}, sourceEventId={}): {}",
            row.getId(), row.getSourceEventId(), msg);
    }

    private void markFailed(BillingEventLog row, String msg) {
        row.setProcessStatus(FinanceConstants.EVT_STATUS_FAILED);
        row.setErrorMsg(truncate(msg, 500));
        row.setProcessedAt(LocalDateTime.now());
        eventLogMapper.updateById(row);
    }

    /**
     * 失败路径的双写 (在 REQUIRES_NEW 事务里调用): event_log 标 FAILED + accrual 写 OPEN_FAILED.
     * 不抛任何异常 — 上层会抛 FinanceException, 这一段独立提交后失效标记已经入库.
     */
    private void writeFailureAudit(BillingEventLog logRow, BillingEventDto event, Long ruleId, String err) {
        markFailed(logRow, err);

        BillingAccrual accrual = new BillingAccrual();
        accrual.setTenantId(event.tenantId());
        accrual.setSourceEventId(event.sourceEventId());
        accrual.setBusinessType(event.businessType());
        accrual.setBusinessId(event.businessId());
        accrual.setRuleId(ruleId);
        accrual.setDirection(event.direction());
        accrual.setCounterpartyId(event.counterpartyId());
        accrual.setGrossAmount(BigDecimal.ZERO);
        accrual.setTaxAmount(BigDecimal.ZERO);
        accrual.setNetAmount(BigDecimal.ZERO);
        accrual.setCurrency(FinanceConstants.CURRENCY_CNY);
        accrual.setStatus(FinanceConstants.ACCRUAL_STATUS_OPEN_FAILED);
        accrual.setErrorMsg(truncate(err, 500));
        try {
            accrualMapper.insert(accrual);
        } catch (DuplicateKeyException dup) {
            log.info("accrue: failed-accrual UNIQUE hit, skip (defensive)", dup);
        }
    }

    /**
     * estimateAndAccrue 失败取证 (REQUIRES_NEW 事务): 写 OPEN_FAILED accrual.
     * <p>与 accrue 内部 writeFailureAudit 区别: 这里 ruleId 不可得 (estimate 阶段未抓), 也不写 event_log
     * (event_log 由 listener 那边管, portal 直调不走 listener).
     */
    private void writeEstimateAndAccrueFailure(BillingEventDto event, String err) {
        BillingAccrual accrual = new BillingAccrual();
        accrual.setTenantId(event.tenantId());
        accrual.setSourceEventId(event.sourceEventId());
        accrual.setBusinessType(event.businessType());
        accrual.setBusinessId(event.businessId());
        accrual.setDirection(event.direction());
        accrual.setCounterpartyId(event.counterpartyId());
        accrual.setGrossAmount(BigDecimal.ZERO);
        accrual.setTaxAmount(BigDecimal.ZERO);
        accrual.setNetAmount(BigDecimal.ZERO);
        accrual.setCurrency(FinanceConstants.CURRENCY_CNY);
        accrual.setStatus(FinanceConstants.ACCRUAL_STATUS_OPEN_FAILED);
        accrual.setErrorMsg(truncate("estimateAndAccrue: " + (err == null ? "unknown" : err), 500));
        try {
            accrualMapper.insert(accrual);
        } catch (DuplicateKeyException dup) {
            log.info("estimateAndAccrue: failed-accrual UNIQUE hit, skip", dup);
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            log.warn("toJson serialization failed, falling back to empty object", e);
            return "{}";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ================================================================
    // 4.5C-1 — 应计冲销 (门户取消订单)
    // ================================================================

    @Override
    public void assertReversible(Long tenantId, String sourceEventId) {
        // 与 accrue / estimateAndAccrue 同一道 guard: tenantId / sourceEventId 任一为 null,
        // 都会让 SQL 变成 tenant_id = NULL / source_event_id = NULL, MySQL 恒不匹配 →
        // 又一次静默放行. 两个入参一起挡, 与 accrue 保持对称.
        if (tenantId == null || sourceEventId == null) {
            log.warn("assertReversible: tenantId/sourceEventId 为 null, 跳过预检放行 (tenant={}, sourceEventId={})",
                tenantId, sourceEventId);
            return;
        }
        BillingAccrual accrual = findBySourceEventId(tenantId, sourceEventId);
        if (accrual == null) {
            // debug 而非 warn: NO_RULE 路径本来就不写 accrual, 查不到是合法常态, warn 会刷屏.
            log.debug("assertReversible: 无 accrual, 放行 (tenant={}, sourceEventId={})", tenantId, sourceEventId);
            return;
        }
        if (isConverted(accrual.getStatus())) {
            // 已开票 / 开票中 — 不可回退。文案面向门户用户, 与 reversal 路径不同。
            throw new FinanceException(FinanceErrorCode.ACCRUAL_ALREADY_CONVERTED,
                "应计 " + accrual.getId() + " 已是 " + accrual.getStatus() + ", 订单不可取消");
        }
        // 查不到 / OPEN / OPEN_FAILED / RELEASED — 都可以 (继续)
    }

    /**
     * 按 (tenant_id, source_event_id, business_type=LAST_MILE) 查 accrual.
     *
     * <p>{@code BillingAccrual.deleted} 带 {@code @TableLogic}, MyBatis-Plus 自动
     * 追加 {@code deleted = 0}, 这里<b>不要</b>手写。
     */
    private BillingAccrual findBySourceEventId(Long tenantId, String sourceEventId) {
        return accrualMapper.selectOne(new LambdaQueryWrapper<BillingAccrual>()
            .eq(BillingAccrual::getTenantId, tenantId)
            .eq(BillingAccrual::getSourceEventId, sourceEventId)
            .eq(BillingAccrual::getBusinessType, FinanceConstants.BIZ_LAST_MILE));
    }

    /**
     * 4.5C-1 — 按 sourceEventId 冲销应计 (门户取消订单时调用).
     *
     * <p>分派表: <br>
     * • 入参任一 null → NOT_FOUND (与 {@link #assertReversible} 一致 — 避免静默错误答案); <br>
     * • 查不到 accrual → NOT_FOUND (createForCustomer 有 "不写 accrual" 路径); <br>
     * • OPEN / OPEN_FAILED → RELEASED (调 {@link AccrualService#release}); <br>
     * • RELEASED → ALREADY_RELEASED (幂等跳过, 已在历史 cancel 中处理过); <br>
     * • CONVERTED / LOCKED → 抛 {@link FinanceErrorCode#ACCRUAL_ALREADY_CONVERTED} (409_004).
     *
     * <p><b>事务边界</b>: 释放走 {@link #requiresNewTx} (REQUIRES_NEW).
     * {@code AccrualService.release} 是 REQUIRED 传播, 直接调会加入调用方
     * (cancelForCustomer) 的事务. 默认配置下 release 一旦抛异常, Spring 会把外层事务标
     * rollback-only (这条链路由 {@code globalRollbackOnParticipationFailure} 等开关决定);
     * dispatch 侧 catch 掉继续走完整流程的话会在 commit 时抛 {@code UnexpectedRollbackException}
     * —— "冲销失败不阻塞取消" 就变成了 "整单取消失败". 独立事务里失败只回滚自己.
     *
     * <p>用 {@link TransactionTemplate} 而非 {@code @Transactional(REQUIRES_NEW)}:
     * 同 bean 自调用会绕过 Spring 代理. 干净样板见
     * {@code com.lumen.finance.service.impl.BillingRuleServiceImpl#commitInvalidAndAudit}.
     *
     * <p><b>代价</b>: 冲销与 DO 状态变更<b>不原子</b> —— 冲销先独立提交, 若外层随后回滚
     * (例如 outbox publish 抛异常), 结果是 "应计已 RELEASED 而订单仍在 PENDING_DISPATCH"
     * (在飞订单被少计费). 这个方向可观测可补, 反向 (虚增收入) 不可逆, 故取此取舍.
     *
     * <p>{@code outcome} 仅供日志/审计, 调用方无需判断 — 冲销成功与否完全由
     * "是否抛异常" 表达. dispatch 侧 (cancelForCustomer) 只需 try/catch, 不要 switch.
     */
    @Override
    public BillingReversalResult reversalForSourceEventId(
            Long tenantId, String sourceEventId, String reason) {
        // 与 assertReversible 一致: tenantId / sourceEventId 任一为 null,
        // 都会让 SQL 命中空, 没有 accrual 也没有 NOT_FOUND 可言 ——
        // 不打这条 warn 就成了一个静默的错误答案 ("本来就没有应计, 幂等成功")。
        if (tenantId == null || sourceEventId == null) {
            log.warn("reversalForSourceEventId: tenantId/sourceEventId 为 null, 记 NOT_FOUND 返 (tenant={}, sourceEventId={})",
                tenantId, sourceEventId);
            return new BillingReversalResult(BillingReversalResult.OUTCOME_NOT_FOUND, null);
        }

        BillingAccrual accrual = findBySourceEventId(tenantId, sourceEventId);
        if (accrual == null) {
            // createForCustomer 有 "不写 accrual" 的路径 (无规则命中 / UNIQUE 冲突)
            return new BillingReversalResult(BillingReversalResult.OUTCOME_NOT_FOUND, null);
        }

        String status = accrual.getStatus();
        if (isConverted(status)) {
            // 文案面向内部日志 ("不可冲销" 记账动作), 与 assertReversible ("订单不可取消")
            // 面向不同读者 — 各处文案独立, 状态判定走 isConverted()。
            throw new FinanceException(FinanceErrorCode.ACCRUAL_ALREADY_CONVERTED,
                "应计 " + accrual.getId() + " 已是 " + status + ", 不可冲销");
        }
        if (FinanceConstants.ACCRUAL_STATUS_RELEASED.equals(status)) {
            // 幂等: 已释放过则跳过 — 放在这里而非让 release 容错, 是为了保住
            // release 对 staff 路径的严格语义 (重复释放仍然报错)。
            return new BillingReversalResult(BillingReversalResult.OUTCOME_ALREADY_RELEASED, accrual.getId());
        }

        // OPEN / OPEN_FAILED → 释放 (走 requiresNewTx, 见 javadoc「事务边界」段)。
        requiresNewTx.executeWithoutResult(txStatus ->
            accrualService.release(tenantId, accrual.getId(), reason, null));
        return new BillingReversalResult(BillingReversalResult.OUTCOME_RELEASED, accrual.getId());
    }

    /**
     * 4.5C-1 — CONVERTED / LOCKED (已开票 / 开票中), 任何路径都不可回退.
     *
     * <p>LOCKED 归入此分支是<b>防御性</b>的: finalize 全程 {@code @Transactional},
     * 理论上观测不到 LOCKED, 收进来是为了万一有残留行时不误动它。
     *
     * <p>状态判定本身抽出来共用 — 两处文案 ("订单不可取消" vs "不可冲销") 由调用点独立把控,
     * 将来加第三个不可回退状态时只改这里。
     */
    private static boolean isConverted(String status) {
        return FinanceConstants.ACCRUAL_STATUS_CONVERTED.equals(status)
            || FinanceConstants.ACCRUAL_STATUS_LOCKED.equals(status);
    }
}
