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

    public BillingEngineServiceImpl(BillingEventLogMapper eventLogMapper,
                                    BillingRuleMapper ruleMapper,
                                    BillingRuleItemMapper itemMapper,
                                    BillingAccrualMapper accrualMapper,
                                    BillingScriptEngine scriptEngine,
                                    ObjectMapper objectMapper,
                                    PlatformTransactionManager txManager,
                                    CustomerRateOverrideService overrideService) {
        this.eventLogMapper = eventLogMapper;
        this.ruleMapper = ruleMapper;
        this.itemMapper = itemMapper;
        this.accrualMapper = accrualMapper;
        this.scriptEngine = scriptEngine;
        this.objectMapper = objectMapper;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.overrideService = overrideService;
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
        // 1. override 优先
        Optional<CustomerRateOverride> overrideOpt = overrideService.findActive(
            tenantId, customerId, businessType, LocalDate.now());
        if (overrideOpt.isPresent()) {
            CustomerRateOverride o = overrideOpt.get();
            String breakdown = String.format("合同价 %s %s (override #%d)",
                o.getCurrency(), o.getBaseAmount().toPlainString(), o.getId());
            return new EstimateResult(o.getBaseAmount(), EstimateResult.SOURCE_OVERRIDE,
                null, o.getId(), o.getCurrency(), breakdown);
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
                throw new FinanceException(FinanceErrorCode.EXPRESSION_INVALID,
                    "estimate: item " + item.getSequence() + " eval failed: " + ex.getMessage());
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
}
