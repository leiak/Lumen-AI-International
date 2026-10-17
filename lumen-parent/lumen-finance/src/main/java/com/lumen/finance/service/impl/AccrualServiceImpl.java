package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.entity.BillingRule;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingAccrualMapper;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.mapper.BillingRuleMapper;
import com.lumen.finance.service.AccrualService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AccrualService 实现 (Iteration 4.3.1, PR3-T3.3).
 *
 * <p>状态机迁移 (与 V26 seed 对齐, {@code transition_name} 仅作审计字段, 运行时只校验
 * {@code fromState -> toState}):
 * <ul>
 *   <li>{@code finalize}: {@code OPEN -> LOCKED} → 生成 invoice → {@code LOCKED -> CONVERTED}</li>
 *   <li>{@code release}:  {@code OPEN -> RELEASED}</li>
 * </ul>
 *
 * <p>单据号生成: 内联 {@code {AR|AP}-{tenantId}-{epochMs}-{seq}} (3 位随机数),
 * 每次插入捕获 {@code DuplicateKeyException} 重试; 3 次全部冲突 →
 * {@link FinanceErrorCode#INVOICE_NO_CONFLICT}. 不引入 {@code lumen-numbering}
 * 依赖, 4.3.2 阶段统一迁移到 {@code NumberGenerator}.
 *
 * <p><b>事务边界</b>: {@code finalize} 是单一 REQUIRED 事务; 状态机校验或 invoice 插入
 * 失败时整个方法回滚, accrual 状态不会卡在 LOCKED.
 *
 * <p><b>异常包装</b>: 状态机底层抛 {@code com.lumen.extension.state.StateMachineException},
 * 已被 {@link #assertSmTransition} 包装为 {@code FinanceException(INVALID_STATE_TRANSITION)};
 * 并发 finalize 触发的 {@code OptimisticLockingFailureException} 同样包装为
 * {@code FinanceException(INVALID_STATE_TRANSITION)}, 由 GlobalExceptionHandler 返回 409
 * 而非 500.
 */
@Slf4j
@Service
public class AccrualServiceImpl implements AccrualService {

    /** invoice insert 重试上限 (含第 1 次). */
    private static final int INVOICE_INSERT_MAX_RETRY = 3;
    /** 单据号随机后缀位数 (与 epochMs 拼接, 降低毫秒级并发冲突概率). */
    private static final int INVOICE_NO_SEQ_LEN = 3;

    private final BillingAccrualMapper accrualMapper;
    private final BillingInvoiceMapper invoiceMapper;
    private final BillingRuleMapper ruleMapper;
    private final StateMachineEngine stateMachine;

    public AccrualServiceImpl(BillingAccrualMapper accrualMapper,
                              BillingInvoiceMapper invoiceMapper,
                              BillingRuleMapper ruleMapper,
                              StateMachineEngine stateMachine) {
        this.accrualMapper = accrualMapper;
        this.invoiceMapper = invoiceMapper;
        this.ruleMapper = ruleMapper;
        this.stateMachine = stateMachine;
    }

    // ---------- list ----------

    @Override
    public List<BillingAccrual> list(Long tenantId, String businessType, String status,
                                     int pageNum, int pageSize) {
        int pn = pageNum < 1 ? 1 : pageNum;
        int ps = pageSize < 1 ? 20 : Math.min(pageSize, 200);
        Page<BillingAccrual> page = Page.of(pn, ps);

        LambdaQueryWrapper<BillingAccrual> q = new LambdaQueryWrapper<BillingAccrual>()
            .eq(BillingAccrual::getTenantId, tenantId)
            .eq(businessType != null && !businessType.isBlank(),
                BillingAccrual::getBusinessType, businessType)
            .eq(status != null && !status.isBlank(),
                BillingAccrual::getStatus, status)
            .orderByDesc(BillingAccrual::getId);

        Page<BillingAccrual> result = accrualMapper.selectPage(page, q);
        List<BillingAccrual> records = result.getRecords();
        return records == null ? List.of() : new ArrayList<>(records);
    }

    // ---------- get ----------

    @Override
    public BillingAccrual get(Long tenantId, Long id) {
        BillingAccrual acc = accrualMapper.selectById(id);
        if (acc == null || !acc.getTenantId().equals(tenantId)) {
            throw new FinanceException(FinanceErrorCode.ACCRUAL_NOT_FOUND);
        }
        return acc;
    }

    // ---------- finalize ----------

    @Override
    @Transactional
    public BillingInvoice finalize(Long tenantId, Long accrualId, Long operatorId) {
        try {
            // 1. 取 accrual, 校验 status=OPEN
            BillingAccrual accrual = get(tenantId, accrualId);
            if (!FinanceConstants.ACCRUAL_STATUS_OPEN.equals(accrual.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Accrual status must be OPEN, got " + accrual.getStatus());
            }

            // 2. 状态机校验 OPEN -> LOCKED
            assertSmTransition(
                FinanceConstants.SM_BILLING_ACCRUAL,
                FinanceConstants.ACCRUAL_STATUS_OPEN,
                FinanceConstants.ACCRUAL_STATUS_LOCKED);

            // 3. 更新 accrual: LOCKED + lockedAt
            accrual.setStatus(FinanceConstants.ACCRUAL_STATUS_LOCKED);
            accrual.setLockedAt(LocalDateTime.now());
            accrualMapper.updateById(accrual);

            // 4. 加载 rule (counterpartyType / ruleVersion)
            BillingRule rule = ruleMapper.selectById(accrual.getRuleId());
            if (rule == null) {
                throw new FinanceException(FinanceErrorCode.RULE_NOT_FOUND,
                    "Rule " + accrual.getRuleId() + " referenced by accrual " + accrualId + " not found");
            }

            // 5. 构造 invoice 并重试插入 (invoice_no UNIQUE 冲突重试; source_accrual_id 冲突 → 已 finalize)
            BillingInvoice invoice = buildInvoice(accrual, rule, tenantId);
            insertInvoiceWithRetry(invoice, accrualId);

            // 6. 状态机校验 LOCKED -> CONVERTED
            assertSmTransition(
                FinanceConstants.SM_BILLING_ACCRUAL,
                FinanceConstants.ACCRUAL_STATUS_LOCKED,
                FinanceConstants.ACCRUAL_STATUS_CONVERTED);

            // 7. 更新 accrual: CONVERTED
            accrual.setStatus(FinanceConstants.ACCRUAL_STATUS_CONVERTED);
            accrualMapper.updateById(accrual);

            log.info("finalize: tenant={} accrualId={} -> invoiceId={} invoiceNo={} operator={}",
                tenantId, accrualId, invoice.getId(), invoice.getInvoiceNo(),
                operatorId == null ? "system" : operatorId);
            return invoice;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent finalize detected for accrual " + accrualId);
        }
    }

    // ---------- release ----------

    @Override
    @Transactional
    public BillingAccrual release(Long tenantId, Long accrualId, String reason, Long operatorId) {
        try {
            // 1. 取 accrual, 校验 status=OPEN
            BillingAccrual accrual = get(tenantId, accrualId);
            if (!FinanceConstants.ACCRUAL_STATUS_OPEN.equals(accrual.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Only OPEN accrual can be released, got " + accrual.getStatus());
            }

            // 2. 状态机校验 OPEN -> RELEASED
            assertSmTransition(
                FinanceConstants.SM_BILLING_ACCRUAL,
                FinanceConstants.ACCRUAL_STATUS_OPEN,
                FinanceConstants.ACCRUAL_STATUS_RELEASED);

            // 3. 更新 accrual: RELEASED + errorMsg=reason (留痕)
            accrual.setStatus(FinanceConstants.ACCRUAL_STATUS_RELEASED);
            accrual.setErrorMsg(reason == null ? "released" : truncate(reason, 500));
            accrualMapper.updateById(accrual);

            log.info("release: tenant={} accrualId={} reason='{}' operator={}",
                tenantId, accrualId, accrual.getErrorMsg(),
                operatorId == null ? "system" : operatorId);
            return accrual;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent release detected for accrual " + accrualId);
        }
    }

    // ---------- helpers ----------

    private BillingInvoice buildInvoice(BillingAccrual accrual, BillingRule rule, Long tenantId) {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setTenantId(tenantId);
        invoice.setDirection(accrual.getDirection());
        invoice.setCounterpartyId(accrual.getCounterpartyId());
        invoice.setCounterpartyType(rule.getCounterpartyType());
        invoice.setBusinessType(accrual.getBusinessType());
        invoice.setSourceAccrualId(accrual.getId());
        invoice.setGrossAmount(accrual.getGrossAmount());
        invoice.setTaxAmount(accrual.getTaxAmount());
        invoice.setNetAmount(accrual.getNetAmount());
        invoice.setCurrency(accrual.getCurrency());
        invoice.setStatus(FinanceConstants.INV_STATUS_DRAFT);
        invoice.setRuleId(rule.getId());
        invoice.setRuleVersion(rule.getVersion() == null ? 0 : rule.getVersion());
        // invoiceNo 由 insertInvoiceWithRetry 在循环里重新生成
        return invoice;
    }

    /**
     * 插入 invoice, 捕获 {@link DuplicateKeyException}:
     * <ul>
     *   <li>{@code uk_source_accrual} 冲突 → 该 accrual 已被 finalize 过一次, 抛
     *       {@code INVALID_STATE_TRANSITION} ("already finalized")</li>
     *   <li>{@code uk_invoice_no} 冲突 → 重试 (重生成 invoice_no), 最多
     *       {@code INVOICE_INSERT_MAX_RETRY} 次, 仍冲突 → {@code INVOICE_NO_CONFLICT}</li>
     * </ul>
     */
    private void insertInvoiceWithRetry(BillingInvoice invoice, Long accrualId) {
        DuplicateKeyException lastDup = null;
        for (int i = 0; i < INVOICE_INSERT_MAX_RETRY; i++) {
            invoice.setInvoiceNo(generateInvoiceNo(invoice.getTenantId(), invoice.getDirection()));
            try {
                invoiceMapper.insert(invoice);
                return;
            } catch (DuplicateKeyException dup) {
                lastDup = dup;
                String msg = dup.getMessage() == null ? "" : dup.getMessage();
                // uk_source_accrual 命中 → 该 accrual 已 finalize 过 (race / replay)
                if (msg.contains("uk_source_accrual")) {
                    log.warn("finalize: accrual {} already has an invoice (source_accrual UK), race detected",
                        accrualId);
                    throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                        "Accrual " + accrualId + " already finalized (invoice exists)");
                }
                // uk_invoice_no 命中 → 重试, 下一轮重生成 invoice_no
                log.info("finalize: invoice_no UNIQUE hit, retry {}/{} (accrualId={})",
                    i + 1, INVOICE_INSERT_MAX_RETRY, accrualId);
            }
        }
        log.warn("finalize: invoice_no conflict after {} retries (accrualId={})",
            INVOICE_INSERT_MAX_RETRY, accrualId, lastDup);
        throw new FinanceException(FinanceErrorCode.INVOICE_NO_CONFLICT,
            "Invoice number conflict after " + INVOICE_INSERT_MAX_RETRY + " retries");
    }

    /**
     * 生成 invoice no: {@code {AR|AP}-{tenantId}-{epochMs}-{seq}}.
     *
     * <p>{@code seq} 是 3 位随机数 (0..999), 降低毫秒级并发冲突. 与 V26 表
     * {@code t_billing_invoice.uk_invoice_no (tenant_id, invoice_no, deleted)} 配合:
     * 不同 tenant 可共用同一 invoice_no.
     */
    private String generateInvoiceNo(Long tenantId, String direction) {
        String prefix = FinanceConstants.DIR_RECEIVABLE.equals(direction) ? "AR" : "AP";
        long ts = System.currentTimeMillis();
        int seq = ThreadLocalRandom.current().nextInt(0, 1000);
        return String.format("%s-%d-%d-%0" + INVOICE_NO_SEQ_LEN + "d", prefix, tenantId, ts, seq);
    }

    /**
     * 包装 StateMachineException → FinanceException (INVALID_STATE_TRANSITION).
     * 失败时 GlobalExceptionHandler 返回 409 (state machine 迁移非法) 而非 500.
     */
    private void assertSmTransition(String smName, String fromState, String toState) {
        try {
            stateMachine.assertTransition(smName, fromState, toState);
        } catch (com.lumen.extension.state.StateMachineException sm) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "State machine [" + smName + "] transition " + fromState + " → " + toState
                    + " not allowed: " + sm.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
