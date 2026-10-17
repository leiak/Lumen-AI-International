package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.service.InvoiceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * InvoiceService 实现 (Iteration 4.3.1, PR3-T3.4).
 *
 * <p>状态机迁移 (与 V26 seed 对齐, 仅校验 {@code fromState -> toState}):
 * <ul>
 *   <li>{@code submit}: {@code DRAFT -> PENDING_L1}, 写 {@code submittedAt}</li>
 *   <li>{@code l1_approve}: {@code PENDING_L1 -> L1_APPROVED}, 写 {@code l1ApproverId / l1ApprovedAt / l1Comment}</li>
 *   <li>{@code l1_reject}: {@code PENDING_L1 -> REJECTED}, comment 必填,
 *       写 {@code l1ApproverId / l1ApprovedAt / l1Comment}</li>
 * </ul>
 *
 * <p>4.3.2 PR2 加 {@code PENDING_L2 / L2_APPROVED} 时, 当前 service 接口保持不变, 仅扩展
 * {@code t_state_transition} seed 与 controller.
 *
 * <p><b>异常包装</b>: 状态机底层抛 {@code com.lumen.extension.state.StateMachineException},
 * 已被 {@link #assertSmTransition} 包装为 {@code FinanceException(INVALID_STATE_TRANSITION)};
 * 并发 updateById 触发的 {@code OptimisticLockingFailureException} 同样包装为
 * {@code FinanceException(INVALID_STATE_TRANSITION)} (409 而非 500).
 *
 * <p><b>权限校验</b>: 在 controller 层 (T3.5) 用 {@code @PreAuthorize} 实现
 * ({@code finance:invoice:submit} / {@code l1_approve}); service 只校验状态机.
 */
@Slf4j
@Service
public class InvoiceServiceImpl implements InvoiceService {

    private final BillingInvoiceMapper invoiceMapper;
    private final StateMachineEngine stateMachine;

    public InvoiceServiceImpl(BillingInvoiceMapper invoiceMapper,
                              StateMachineEngine stateMachine) {
        this.invoiceMapper = invoiceMapper;
        this.stateMachine = stateMachine;
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

            // 3. 更新 invoice: PENDING_L1 + submittedAt=now
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

    // ---------- l1Approve: PENDING_L1 -> L1_APPROVED ----------

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

            // 2. 状态机校验 PENDING_L1 -> L1_APPROVED
            assertSmTransition(
                FinanceConstants.SM_BILLING_INVOICE,
                FinanceConstants.INV_STATUS_PENDING_L1,
                FinanceConstants.INV_STATUS_L1_APPROVED);

            // 3. 更新 invoice: L1_APPROVED + 审批人/时间/comment (comment 可 null)
            inv.setStatus(FinanceConstants.INV_STATUS_L1_APPROVED);
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
