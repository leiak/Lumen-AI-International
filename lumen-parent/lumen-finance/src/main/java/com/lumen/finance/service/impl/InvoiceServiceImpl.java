package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.service.ExchangeRateService;
import com.lumen.finance.service.InvoiceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * InvoiceService 实现 (Iteration 4.3.1, PR3-T3.4; 4.3.2 PR3 增 L2).
 *
 * <p>状态机迁移 (与 V26 + V30 seed 对齐, 仅校验 {@code fromState -> toState}):
 * <ul>
 *   <li>{@code submit}:     {@code DRAFT      -> PENDING_L1},  写 {@code submittedAt}</li>
 *   <li>{@code l1_approve}: {@code PENDING_L1 -> PENDING_L2}  (4.3.2 PR3 起, 终态改为 PENDING_L2),
 *       写 {@code l1ApproverId / l1ApprovedAt / l1Comment}</li>
 *   <li>{@code l1_reject}:  {@code PENDING_L1 -> REJECTED},   comment 必填,
 *       写 {@code l1ApproverId / l1ApprovedAt / l1Comment}</li>
 *   <li>{@code l2_approve}: {@code PENDING_L2 -> L2_APPROVED}, 写 {@code l2ApproverId / l2ApprovedAt / l2Comment};
 *       硬约束: 同操作员不能同时做 L1 + L2</li>
 *   <li>{@code l2_reject}:  {@code PENDING_L2 -> REJECTED},    comment 必填,
 *       写 {@code l2ApproverId / l2ApprovedAt / l2Comment}; 硬约束同上</li>
 * </ul>
 *
 * <p><b>异常包装</b>: 状态机底层抛 {@code com.lumen.extension.state.StateMachineException},
 * 已被 {@link #assertSmTransition} 包装为 {@code FinanceException(INVALID_STATE_TRANSITION)};
 * 并发 updateById 触发的 {@code OptimisticLockingFailureException} 同样包装为
 * {@code FinanceException(INVALID_STATE_TRANSITION)} (409 而非 500).
 *
 * <p><b>权限校验</b>: 在 controller 层 (T3.5) 用 {@code @PreAuthorize} 实现
 * ({@code finance:invoice:submit} / {@code l1_approve} / {@code l2_approve});
 * service 只校验状态机 + 同人约束.
 */
@Slf4j
@Service
public class InvoiceServiceImpl implements InvoiceService {

    private final BillingInvoiceMapper invoiceMapper;
    private final StateMachineEngine stateMachine;
    private final ExchangeRateService exchangeRateService;

    public InvoiceServiceImpl(BillingInvoiceMapper invoiceMapper,
                              StateMachineEngine stateMachine,
                              ExchangeRateService exchangeRateService) {
        this.invoiceMapper = invoiceMapper;
        this.stateMachine = stateMachine;
        this.exchangeRateService = exchangeRateService;
    }

    // ---------- list ----------

    @Override
    public List<BillingInvoice> list(Long tenantId, String status, int pageNum, int pageSize) {
        int pn = pageNum < 1 ? 1 : pageNum;
        int ps = pageSize < 1 ? 20 : Math.min(pageSize, 200);
        Page<BillingInvoice> page = Page.of(pn, ps);

        LambdaQueryWrapper<BillingInvoice> q = new LambdaQueryWrapper<BillingInvoice>()
            .eq(BillingInvoice::getTenantId, tenantId)
            .eq(status != null && !status.isBlank(),
                BillingInvoice::getStatus, status)
            .orderByDesc(BillingInvoice::getId);

        Page<BillingInvoice> result = invoiceMapper.selectPage(page, q);
        List<BillingInvoice> records = result.getRecords();
        return records == null ? List.of() : new ArrayList<>(records);
    }

    // ---------- get ----------

    @Override
    public BillingInvoice get(Long tenantId, Long id) {
        BillingInvoice inv = invoiceMapper.selectById(id);
        if (inv == null || !inv.getTenantId().equals(tenantId)) {
            throw new FinanceException(FinanceErrorCode.INVOICE_NOT_FOUND);
        }
        return inv;
    }

    // ---------- submit: DRAFT -> PENDING_L1 ----------

    @Override
    @Transactional
    public BillingInvoice submit(Long tenantId, Long id, Long operatorId) {
        try {
            // 1. 取 invoice, 校验 status=DRAFT
            BillingInvoice inv = get(tenantId, id);
            if (!FinanceConstants.INV_STATUS_DRAFT.equals(inv.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Invoice status must be DRAFT to submit, got " + inv.getStatus());
            }

            // 2. 状态机校验 DRAFT -> PENDING_L1
            assertSmTransition(
                FinanceConstants.SM_BILLING_INVOICE,
                FinanceConstants.INV_STATUS_DRAFT,
                FinanceConstants.INV_STATUS_PENDING_L1);

            // 2.5 锁定汇率 (spec §5.3, 4.3.2 PR3): 单据提交时回填 fx_rate_at_creation.
            // CNY→CNY 短路为 1.0; 其它币种查 ExchangeRateService.getRate (缺失抛
            // RATE_UNAVAILABLE 400_007, 由 GlobalExceptionHandler 返回 400).
            if (FinanceConstants.CURRENCY_CNY.equals(inv.getCurrency())) {
                inv.setFxRateAtCreation(BigDecimal.ONE);
            } else {
                BigDecimal rate = exchangeRateService.getRate(
                    inv.getCurrency(), FinanceConstants.CURRENCY_CNY, LocalDate.now());
                inv.setFxRateAtCreation(rate);
            }

            // 3. 更新 invoice: PENDING_L1 + submittedAt=now + fx_rate_at_creation
            inv.setStatus(FinanceConstants.INV_STATUS_PENDING_L1);
            inv.setSubmittedAt(LocalDateTime.now());
            invoiceMapper.updateById(inv);

            log.info("submit: tenant={} invoiceId={} operator={}",
                tenantId, id, operatorId == null ? "system" : operatorId);
            return inv;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent submit detected for invoice " + id);
        }
    }

    // ---------- l1Approve: PENDING_L1 -> PENDING_L2 (4.3.2 PR3 起, 终态改为 PENDING_L2) ----------

    @Override
    @Transactional
    public BillingInvoice l1Approve(Long tenantId, Long id, Long operatorId, String comment) {
        try {
            // 1. 取 invoice, 校验 status=PENDING_L1
            BillingInvoice inv = get(tenantId, id);
            if (!FinanceConstants.INV_STATUS_PENDING_L1.equals(inv.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Invoice status must be PENDING_L1 to approve, got " + inv.getStatus());
            }

            // 2. 状态机校验 PENDING_L1 -> PENDING_L2 (4.3.2 V30 seed; 1 级终态改为 PENDING_L2)
            assertSmTransition(
                FinanceConstants.SM_BILLING_INVOICE,
                FinanceConstants.INV_STATUS_PENDING_L1,
                FinanceConstants.INV_STATUS_PENDING_L2);

            // 3. 更新 invoice: PENDING_L2 + 审批人/时间/comment (comment 可 null)
            inv.setStatus(FinanceConstants.INV_STATUS_PENDING_L2);
            inv.setL1ApproverId(operatorId);
            inv.setL1ApprovedAt(LocalDateTime.now());
            inv.setL1Comment(truncate(comment, FinanceConstants.INVOICE_L1_COMMENT_MAX_LEN));
            invoiceMapper.updateById(inv);

            log.info("l1Approve: tenant={} invoiceId={} operator={}",
                tenantId, id, operatorId == null ? "system" : operatorId);
            return inv;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l1Approve detected for invoice " + id);
        }
    }

    // ---------- l1Reject: PENDING_L1 -> REJECTED (comment 必填) ----------

    @Override
    @Transactional
    public BillingInvoice l1Reject(Long tenantId, Long id, Long operatorId, String comment) {
        try {
            // 0. 拒绝原因必填 (非空 + 非空白)
            if (comment == null || comment.isBlank()) {
                throw new FinanceException(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED,
                    "l1Reject requires non-blank comment");
            }

            // 1. 取 invoice, 校验 status=PENDING_L1
            BillingInvoice inv = get(tenantId, id);
            if (!FinanceConstants.INV_STATUS_PENDING_L1.equals(inv.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Invoice status must be PENDING_L1 to reject, got " + inv.getStatus());
            }

            // 2. 状态机校验 PENDING_L1 -> REJECTED
            assertSmTransition(
                FinanceConstants.SM_BILLING_INVOICE,
                FinanceConstants.INV_STATUS_PENDING_L1,
                FinanceConstants.INV_STATUS_REJECTED);

            // 3. 更新 invoice: REJECTED + 审批人/时间/comment
            inv.setStatus(FinanceConstants.INV_STATUS_REJECTED);
            inv.setL1ApproverId(operatorId);
            inv.setL1ApprovedAt(LocalDateTime.now());
            inv.setL1Comment(truncate(comment, FinanceConstants.INVOICE_L1_COMMENT_MAX_LEN));
            invoiceMapper.updateById(inv);

            log.info("l1Reject: tenant={} invoiceId={} operator={} comment='{}'",
                tenantId, id, operatorId == null ? "system" : operatorId,
                inv.getL1Comment());
            return inv;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l1Reject detected for invoice " + id);
        }
    }

    // ---------- l2Approve: PENDING_L2 -> L2_APPROVED (4.3.2 PR3) ----------

    @Override
    @Transactional
    public BillingInvoice l2Approve(Long tenantId, Long id, Long operatorId, String comment) {
        try {
            // 1. 取 invoice, 校验 status=PENDING_L2
            BillingInvoice inv = get(tenantId, id);
            if (!FinanceConstants.INV_STATUS_PENDING_L2.equals(inv.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Invoice status must be PENDING_L2 to L2-approve, got " + inv.getStatus());
            }

            // 2. 硬约束: L1 与 L2 不能同一操作员 (避免单人双签)
            if (inv.getL1ApproverId() != null && inv.getL1ApproverId().equals(operatorId)) {
                throw new FinanceException(FinanceErrorCode.L1_L2_SAME_OPERATOR,
                    "Same operator cannot L1 and L2 approve invoice " + id
                        + " (l1ApproverId=" + inv.getL1ApproverId() + ", operator=" + operatorId + ")");
            }

            // 3. 状态机校验 PENDING_L2 -> L2_APPROVED (V30 seed)
            assertSmTransition(
                FinanceConstants.SM_BILLING_INVOICE,
                FinanceConstants.INV_STATUS_PENDING_L2,
                FinanceConstants.INV_STATUS_L2_APPROVED);

            // 4. 更新 invoice: L2_APPROVED + 审批人/时间/comment (comment 可 null)
            inv.setStatus(FinanceConstants.INV_STATUS_L2_APPROVED);
            inv.setL2ApproverId(operatorId);
            inv.setL2ApprovedAt(LocalDateTime.now());
            inv.setL2Comment(truncate(comment, FinanceConstants.INVOICE_L2_COMMENT_MAX_LEN));
            invoiceMapper.updateById(inv);

            log.info("l2Approve: tenant={} invoiceId={} operator={}",
                tenantId, id, operatorId == null ? "system" : operatorId);
            return inv;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l2Approve detected for invoice " + id);
        }
    }

    // ---------- l2Reject: PENDING_L2 -> REJECTED (comment 必填) (4.3.2 PR3) ----------

    @Override
    @Transactional
    public BillingInvoice l2Reject(Long tenantId, Long id, Long operatorId, String comment) {
        try {
            // 0. 拒绝原因必填 (非空 + 非空白)
            if (comment == null || comment.isBlank()) {
                throw new FinanceException(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED,
                    "l2Reject requires non-blank comment");
            }

            // 1. 取 invoice, 校验 status=PENDING_L2
            BillingInvoice inv = get(tenantId, id);
            if (!FinanceConstants.INV_STATUS_PENDING_L2.equals(inv.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Invoice status must be PENDING_L2 to L2-reject, got " + inv.getStatus());
            }

            // 2. 硬约束: L1 与 L2 不能同一操作员
            if (inv.getL1ApproverId() != null && inv.getL1ApproverId().equals(operatorId)) {
                throw new FinanceException(FinanceErrorCode.L1_L2_SAME_OPERATOR,
                    "Same operator cannot L1 and L2 reject invoice " + id
                        + " (l1ApproverId=" + inv.getL1ApproverId() + ", operator=" + operatorId + ")");
            }

            // 3. 状态机校验 PENDING_L2 -> REJECTED (V30 seed)
            assertSmTransition(
                FinanceConstants.SM_BILLING_INVOICE,
                FinanceConstants.INV_STATUS_PENDING_L2,
                FinanceConstants.INV_STATUS_REJECTED);

            // 4. 更新 invoice: REJECTED + 审批人/时间/comment (截断到 maxLen)
            inv.setStatus(FinanceConstants.INV_STATUS_REJECTED);
            inv.setL2ApproverId(operatorId);
            inv.setL2ApprovedAt(LocalDateTime.now());
            inv.setL2Comment(truncate(comment, FinanceConstants.INVOICE_L2_COMMENT_MAX_LEN));
            invoiceMapper.updateById(inv);

            log.info("l2Reject: tenant={} invoiceId={} operator={} comment='{}'",
                tenantId, id, operatorId == null ? "system" : operatorId,
                inv.getL2Comment());
            return inv;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l2Reject detected for invoice " + id);
        }
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
