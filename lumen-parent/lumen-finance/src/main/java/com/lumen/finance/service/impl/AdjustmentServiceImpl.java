package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.engine.CurrencyConverter;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingAdjustmentMapper;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.service.AdjustmentService;
import com.lumen.finance.service.ExchangeRateService;
import com.lumen.finance.service.TaxRateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AdjustmentService 实现 (Iteration 4.3.2, PR4-T4.1).
 *
 * <p>状态机迁移 (与 V30 seed 对齐, 仅校验 {@code fromState -> toState}):
 * <ul>
 *   <li>{@code submit}:     {@code DRAFT      -> PENDING_L1},  写 {@code errorMsg="submitted..."}</li>
 *   <li>{@code l1Approve}: {@code PENDING_L1 -> PENDING_L2}, 写 L1 元信息</li>
 *   <li>{@code l1Reject}:  {@code PENDING_L1 -> REJECTED},  comment 必填, 写 L1 元信息</li>
 *   <li>{@code l2Approve}: {@code PENDING_L2 -> L2_APPROVED}, 写 L2 元信息; 同操作员拦截</li>
 *   <li>{@code l2Reject}:  {@code PENDING_L2 -> REJECTED},  comment 必填, 写 L2 元信息; 同操作员拦截</li>
 *   <li>{@code markPosted}: {@code L2_APPROVED -> POSTED},  errorMsg 留痕</li>
 * </ul>
 *
 * <p><b>create 入参校验</b>:
 * <ul>
 *   <li>{@code amount > 0} → {@code ADJUSTMENT_AMOUNT_INVALID}</li>
 *   <li>{@code reason.length() >= ADJUSTMENT_REASON_MIN_LEN} → {@code ADJUSTMENT_REASON_REQUIRED}</li>
 *   <li>源单据存在 + 同租户 → {@code INVOICE_NOT_FOUND}</li>
 *   <li>源单据 status == L2_APPROVED → {@code ADJUSTMENT_SOURCE_NOT_APPROVED}</li>
 *   <li>源单据 currency == adjustment.currency → {@code ADJUSTMENT_CURRENCY_MISMATCH}</li>
 * </ul>
 *
 * <p><b>create 计算</b>: amount 视为含税输入, 通过 {@link CurrencyConverter} 拆税;
 * taxRate 通过 {@link TaxRateService#getActiveRate}; 汇率继承源单据 {@code fx_rate_at_creation}
 * (由 submit 时锁定, 4.3.2 PR3 引入).
 *
 * <p><b>adjustment_no 生成</b>: 内联 {@code ADJ-{tenantId}-{epochMs}-{seq3}} (与 InvoiceService
 * generateInvoiceNo 同模式, 3 位随机数), 由 {@code uk_adj_no} 保证唯一.
 *
 * <p><b>异常包装</b>: 状态机底层抛 {@code com.lumen.extension.state.StateMachineException},
 * 已被 {@link #assertSmTransition} 包装为 {@code FinanceException(INVALID_STATE_TRANSITION)};
 * 并发 updateById 触发的 {@code OptimisticLockingFailureException} 同样包装为
 * {@code FinanceException(INVALID_STATE_TRANSITION)} (409 而非 500).
 *
 * <p><b>权限校验</b>: 在 controller 层 (T4.2) 用 {@code @PreAuthorize} 实现
 * ({@code finance:adjustment:create / submit / l1_approve / l2_approve});
 * service 只校验数据 + 状态机 + 同人约束.
 */
@Slf4j
@Service
public class AdjustmentServiceImpl implements AdjustmentService {

    /** adjustment_no 序列后缀位数 (3 位随机数, 与 InvoiceService generateInvoiceNo 对齐). */
    private static final int ADJ_NO_SEQ_LEN = 3;

    private final BillingAdjustmentMapper adjustmentMapper;
    private final BillingInvoiceMapper invoiceMapper;
    private final StateMachineEngine stateMachine;
    private final ExchangeRateService exchangeRateService;
    private final TaxRateService taxRateService;

    public AdjustmentServiceImpl(BillingAdjustmentMapper adjustmentMapper,
                                 BillingInvoiceMapper invoiceMapper,
                                 StateMachineEngine stateMachine,
                                 ExchangeRateService exchangeRateService,
                                 TaxRateService taxRateService) {
        this.adjustmentMapper = adjustmentMapper;
        this.invoiceMapper = invoiceMapper;
        this.stateMachine = stateMachine;
        this.exchangeRateService = exchangeRateService;
        this.taxRateService = taxRateService;
    }

    // ---------- create: insert DRAFT ----------

    @Override
    @Transactional
    public BillingAdjustment create(Long tenantId, Long sourceInvoiceId, String adjustmentType,
                                    BigDecimal amount, String reason, Long operatorId) {
        try {
            // 0. 调整类型校验
            if (!FinanceConstants.ADJ_TYPE_CREDIT.equals(adjustmentType)
                && !FinanceConstants.ADJ_TYPE_DEBIT.equals(adjustmentType)) {
                throw new FinanceException(FinanceErrorCode.EXPRESSION_INVALID,
                    "adjustmentType must be CREDIT or DEBIT, got: " + adjustmentType);
            }

            // 1. amount > 0
            if (amount == null || amount.signum() <= 0) {
                throw new FinanceException(FinanceErrorCode.ADJUSTMENT_AMOUNT_INVALID,
                    "Adjustment amount must be positive, got " + amount);
            }

            // 2. reason length >= 10
            if (reason == null
                || reason.length() < FinanceConstants.ADJUSTMENT_REASON_MIN_LEN
                || reason.isBlank()) {
                throw new FinanceException(FinanceErrorCode.ADJUSTMENT_REASON_REQUIRED,
                    "Adjustment reason must be at least "
                        + FinanceConstants.ADJUSTMENT_REASON_MIN_LEN + " chars, got "
                        + (reason == null ? 0 : reason.length()));
            }

            // 3. 源单据存在 + 同租户
            BillingInvoice source = invoiceMapper.selectById(sourceInvoiceId);
            if (source == null || !source.getTenantId().equals(tenantId)) {
                // 用现有 invoice 错误码 (与 InvoiceService 行为一致)
                throw new FinanceException(FinanceErrorCode.INVOICE_NOT_FOUND,
                    "Source invoice " + sourceInvoiceId + " not found for tenant " + tenantId);
            }

            // 4. 源单据必须 L2_APPROVED
            if (!FinanceConstants.INV_STATUS_L2_APPROVED.equals(source.getStatus())) {
                throw new FinanceException(FinanceErrorCode.ADJUSTMENT_SOURCE_NOT_APPROVED,
                    "Source invoice " + sourceInvoiceId + " status must be L2_APPROVED, got "
                        + source.getStatus());
            }

            // 5. currency 一致 (不支持跨币种调账)
            String currency = source.getCurrency();
            if (!FinanceConstants.CURRENCY_CNY.equals(currency)
                && !"USD".equals(currency)) {
                // 4.3.2 仅启用 CNY / USD, 其它币种显式拒绝
                throw new FinanceException(FinanceErrorCode.ADJUSTMENT_CURRENCY_MISMATCH,
                    "Unsupported adjustment currency: " + currency);
            }

            // 6. 取税率 + 计算税额/净额
            BigDecimal taxRate = taxRateService.getActiveRate(currency, LocalDate.now());
            BigDecimal taxAmount = CurrencyConverter.calculateTax(amount, taxRate);
            BigDecimal netAmount = amount.subtract(taxAmount)
                .setScale(CurrencyConverter.SCALE, java.math.RoundingMode.HALF_UP);

            // 7. 汇率继承源单据的 fx_rate_at_creation
            BigDecimal fxRate = source.getFxRateAtCreation() == null
                ? BigDecimal.ONE.setScale(CurrencyConverter.SCALE, java.math.RoundingMode.HALF_UP)
                : source.getFxRateAtCreation();

            // 8. 构造 + 插入
            BillingAdjustment adj = BillingAdjustment.builder()
                .tenantId(tenantId)
                .adjustmentNo(generateAdjustmentNo(tenantId))
                .sourceInvoiceId(sourceInvoiceId)
                .adjustmentType(adjustmentType)
                .amount(amount)
                .currency(currency)
                .fxRateAtCreation(fxRate)
                .taxRate(taxRate)
                .grossAmount(amount)
                .taxAmount(taxAmount)
                .netAmount(netAmount)
                .reason(reason)
                .status(FinanceConstants.ADJ_STATUS_DRAFT)
                .build();
            adjustmentMapper.insert(adj);

            log.info("create: tenant={} adjustmentId={} no={} sourceInvoiceId={} type={} amount={} {} operator={}",
                tenantId, adj.getId(), adj.getAdjustmentNo(),
                sourceInvoiceId, adjustmentType, amount, currency,
                operatorId == null ? "system" : operatorId);
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent create detected for adjustment on invoice " + sourceInvoiceId);
        }
    }

    // ---------- submit: DRAFT -> PENDING_L1 ----------

    @Override
    @Transactional
    public BillingAdjustment submit(Long tenantId, Long adjustmentId, Long operatorId) {
        try {
            // 1. 取 adjustment, 校验 status=DRAFT
            BillingAdjustment adj = get(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_DRAFT.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be DRAFT to submit, got " + adj.getStatus());
            }

            // 2. 状态机校验 DRAFT -> PENDING_L1
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_DRAFT,
                FinanceConstants.ADJ_STATUS_PENDING_L1);

            // 3. 更新 adjustment: PENDING_L1 + errorMsg 留痕
            adj.setStatus(FinanceConstants.ADJ_STATUS_PENDING_L1);
            adj.setErrorMsg("submitted at " + LocalDateTime.now() + " by operator "
                + (operatorId == null ? "system" : operatorId));
            adjustmentMapper.updateById(adj);

            log.info("submit: tenant={} adjustmentId={} operator={}",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId);
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent submit detected for adjustment " + adjustmentId);
        }
    }

    // ---------- l1Approve: PENDING_L1 -> PENDING_L2 ----------

    @Override
    @Transactional
    public BillingAdjustment l1Approve(Long tenantId, Long adjustmentId, Long operatorId, String comment) {
        try {
            // 1. 取 adjustment, 校验 status=PENDING_L1
            BillingAdjustment adj = get(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_PENDING_L1.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be PENDING_L1 to l1-approve, got " + adj.getStatus());
            }

            // 2. 状态机校验 PENDING_L1 -> PENDING_L2
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_PENDING_L1,
                FinanceConstants.ADJ_STATUS_PENDING_L2);

            // 3. 更新 adjustment: PENDING_L2 + L1 元信息
            adj.setStatus(FinanceConstants.ADJ_STATUS_PENDING_L2);
            adj.setL1ApproverId(operatorId);
            adj.setL1ApprovedAt(LocalDateTime.now());
            adj.setL1Comment(truncate(comment, FinanceConstants.ADJUSTMENT_L1_COMMENT_MAX_LEN));
            adjustmentMapper.updateById(adj);

            log.info("l1Approve: tenant={} adjustmentId={} operator={}",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId);
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l1Approve detected for adjustment " + adjustmentId);
        }
    }

    // ---------- l1Reject: PENDING_L1 -> REJECTED (comment 必填) ----------

    @Override
    @Transactional
    public BillingAdjustment l1Reject(Long tenantId, Long adjustmentId, Long operatorId, String comment) {
        try {
            // 0. 拒绝原因必填
            if (comment == null || comment.isBlank()) {
                throw new FinanceException(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED,
                    "l1Reject requires non-blank comment");
            }

            // 1. 取 adjustment, 校验 status=PENDING_L1
            BillingAdjustment adj = get(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_PENDING_L1.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be PENDING_L1 to l1-reject, got " + adj.getStatus());
            }

            // 2. 状态机校验 PENDING_L1 -> REJECTED
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_PENDING_L1,
                FinanceConstants.ADJ_STATUS_REJECTED);

            // 3. 更新 adjustment: REJECTED + L1 元信息
            adj.setStatus(FinanceConstants.ADJ_STATUS_REJECTED);
            adj.setL1ApproverId(operatorId);
            adj.setL1ApprovedAt(LocalDateTime.now());
            adj.setL1Comment(truncate(comment, FinanceConstants.ADJUSTMENT_L1_COMMENT_MAX_LEN));
            adjustmentMapper.updateById(adj);

            log.info("l1Reject: tenant={} adjustmentId={} operator={} comment='{}'",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId,
                adj.getL1Comment());
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l1Reject detected for adjustment " + adjustmentId);
        }
    }

    // ---------- l2Approve: PENDING_L2 -> L2_APPROVED ----------

    @Override
    @Transactional
    public BillingAdjustment l2Approve(Long tenantId, Long adjustmentId, Long operatorId, String comment) {
        try {
            // 1. 取 adjustment, 校验 status=PENDING_L2
            BillingAdjustment adj = get(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_PENDING_L2.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be PENDING_L2 to l2-approve, got " + adj.getStatus());
            }

            // 2. 硬约束: L1 与 L2 不能同一操作员
            if (adj.getL1ApproverId() != null && adj.getL1ApproverId().equals(operatorId)) {
                throw new FinanceException(FinanceErrorCode.L1_L2_SAME_OPERATOR,
                    "Same operator cannot L1 and L2 approve adjustment " + adjustmentId
                        + " (l1ApproverId=" + adj.getL1ApproverId() + ", operator=" + operatorId + ")");
            }

            // 3. 状态机校验 PENDING_L2 -> L2_APPROVED
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_PENDING_L2,
                FinanceConstants.ADJ_STATUS_L2_APPROVED);

            // 4. 更新 adjustment: L2_APPROVED + L2 元信息
            adj.setStatus(FinanceConstants.ADJ_STATUS_L2_APPROVED);
            adj.setL2ApproverId(operatorId);
            adj.setL2ApprovedAt(LocalDateTime.now());
            adj.setL2Comment(truncate(comment, FinanceConstants.ADJUSTMENT_L2_COMMENT_MAX_LEN));
            adjustmentMapper.updateById(adj);

            log.info("l2Approve: tenant={} adjustmentId={} operator={}",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId);
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l2Approve detected for adjustment " + adjustmentId);
        }
    }

    // ---------- l2Reject: PENDING_L2 -> REJECTED (comment 必填) ----------

    @Override
    @Transactional
    public BillingAdjustment l2Reject(Long tenantId, Long adjustmentId, Long operatorId, String comment) {
        try {
            // 0. 拒绝原因必填
            if (comment == null || comment.isBlank()) {
                throw new FinanceException(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED,
                    "l2Reject requires non-blank comment");
            }

            // 1. 取 adjustment, 校验 status=PENDING_L2
            BillingAdjustment adj = get(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_PENDING_L2.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be PENDING_L2 to l2-reject, got " + adj.getStatus());
            }

            // 2. 硬约束: L1 与 L2 不能同一操作员
            if (adj.getL1ApproverId() != null && adj.getL1ApproverId().equals(operatorId)) {
                throw new FinanceException(FinanceErrorCode.L1_L2_SAME_OPERATOR,
                    "Same operator cannot L1 and L2 reject adjustment " + adjustmentId
                        + " (l1ApproverId=" + adj.getL1ApproverId() + ", operator=" + operatorId + ")");
            }

            // 3. 状态机校验 PENDING_L2 -> REJECTED
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_PENDING_L2,
                FinanceConstants.ADJ_STATUS_REJECTED);

            // 4. 更新 adjustment: REJECTED + L2 元信息
            adj.setStatus(FinanceConstants.ADJ_STATUS_REJECTED);
            adj.setL2ApproverId(operatorId);
            adj.setL2ApprovedAt(LocalDateTime.now());
            adj.setL2Comment(truncate(comment, FinanceConstants.ADJUSTMENT_L2_COMMENT_MAX_LEN));
            adjustmentMapper.updateById(adj);

            log.info("l2Reject: tenant={} adjustmentId={} operator={} comment='{}'",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId,
                adj.getL2Comment());
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l2Reject detected for adjustment " + adjustmentId);
        }
    }

    // ---------- markPosted: L2_APPROVED -> POSTED ----------

    @Override
    @Transactional
    public BillingAdjustment markPosted(Long tenantId, Long adjustmentId, Long operatorId) {
        try {
            // 1. 取 adjustment, 校验 status=L2_APPROVED
            BillingAdjustment adj = get(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_L2_APPROVED.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be L2_APPROVED to mark posted, got " + adj.getStatus());
            }

            // 2. 状态机校验 L2_APPROVED -> POSTED
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_L2_APPROVED,
                FinanceConstants.ADJ_STATUS_POSTED);

            // 3. 更新 adjustment: POSTED + errorMsg 留痕
            adj.setStatus(FinanceConstants.ADJ_STATUS_POSTED);
            adj.setErrorMsg("posted at " + LocalDateTime.now() + " by operator "
                + (operatorId == null ? "system" : operatorId));
            adjustmentMapper.updateById(adj);

            log.info("markPosted: tenant={} adjustmentId={} operator={}",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId);
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent markPosted detected for adjustment " + adjustmentId);
        }
    }

    // ---------- get ----------

    @Override
    public BillingAdjustment get(Long tenantId, Long adjustmentId) {
        BillingAdjustment adj = adjustmentMapper.selectById(adjustmentId);
        if (adj == null || !adj.getTenantId().equals(tenantId)) {
            throw new FinanceException(FinanceErrorCode.ADJUSTMENT_NOT_FOUND);
        }
        return adj;
    }

    // ---------- list ----------

    @Override
    public List<BillingAdjustment> list(Long tenantId, String status, int page, int size) {
        int pn = page < 1 ? 1 : page;
        int ps = size < 1 ? 20 : Math.min(size, 200);
        Page<BillingAdjustment> pg = Page.of(pn, ps);

        LambdaQueryWrapper<BillingAdjustment> q = new LambdaQueryWrapper<BillingAdjustment>()
            .eq(BillingAdjustment::getTenantId, tenantId)
            .eq(status != null && !status.isBlank(),
                BillingAdjustment::getStatus, status)
            .orderByDesc(BillingAdjustment::getId);

        Page<BillingAdjustment> result = adjustmentMapper.selectPage(pg, q);
        List<BillingAdjustment> records = result.getRecords();
        return records == null ? List.of() : new ArrayList<>(records);
    }

    // ---------- helpers ----------

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

    /**
     * 生成 adjustment no: {@code ADJ-{tenantId}-{epochMs}-{seq}}.
     *
     * <p>与 {@code AccrualServiceImpl.generateInvoiceNo} 同模式, 由 V29 表
     * {@code t_billing_adjustment.uk_adj_no (tenant_id, adjustment_no)} 保证唯一.
     */
    private String generateAdjustmentNo(Long tenantId) {
        long ts = System.currentTimeMillis();
        int seq = java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 1000);
        return String.format("ADJ-%d-%d-%0" + ADJ_NO_SEQ_LEN + "d", tenantId, ts, seq);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
