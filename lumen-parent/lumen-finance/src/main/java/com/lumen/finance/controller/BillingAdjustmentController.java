package com.lumen.finance.controller;

import com.lumen.common.api.R;
import com.lumen.common.security.SecurityUtil;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.service.AdjustmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 调账单 (credit / debit note) REST 控制器 (Iteration 4.3.2, PR4-T4.2).
 *
 * <p>路径 {@code /api/v1/finance/adjustments}; 9 个端点 (list + get + create + submit
 * + l1-approve + l1-reject + l2-approve + l2-reject + mark-posted).
 *
 * <p>权限按动作细粒度 (V30 RBAC seed):
 * <ul>
 *   <li>{@code finance:adjustment:view}        — list + get</li>
 *   <li>{@code finance:adjustment:create}      — create</li>
 *   <li>{@code finance:adjustment:submit}      — submit (DRAFT → PENDING_L1)</li>
 *   <li>{@code finance:adjustment:l1_approve}  — l1-approve + l1-reject (PENDING_L1 → PENDING_L2 / REJECTED)</li>
 *   <li>{@code finance:adjustment:l2_approve}  — l2-approve + l2-reject + mark-posted
 *       (PENDING_L2 → L2_APPROVED / REJECTED, L2_APPROVED → POSTED)</li>
 * </ul>
 *
 * <p>对应 {@link AdjustmentService}. tenantId 从 {@link TenantContext} 取,
 * operatorId 从 {@link SecurityUtil} 取.
 *
 * <p>l1Reject / l2Reject 的 {@code comment} 必填校验由 service 层
 * ({@code AdjustmentService.l1Reject / l2Reject}) 完成 — 缺 / 空时抛
 * {@code ADJUSTMENT_REJECT_REASON_REQUIRED} → 400.
 */
@RestController
@RequestMapping("/api/v1/finance/adjustments")
@RequiredArgsConstructor
public class BillingAdjustmentController {

    private final AdjustmentService adjustmentService;

    /**
     * 分页列出指定租户的调账单 (可选 status 过滤), 按 id desc 排序.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('finance:adjustment:view')")
    public R<List<BillingAdjustment>> list(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(defaultValue = "20") Integer size
    ) {
        Long tenantId = TenantContext.require();
        return R.ok(adjustmentService.list(tenantId, status, page, size));
    }

    /**
     * 按 id 取调账单. 缺失 / 跨租户 → {@code ADJUSTMENT_NOT_FOUND}.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('finance:adjustment:view')")
    public R<BillingAdjustment> get(@PathVariable Long id) {
        Long tenantId = TenantContext.require();
        return R.ok(adjustmentService.get(tenantId, id));
    }

    /**
     * 创建调账单 (DRAFT 态). 请求体见 {@link CreateAdjustmentRequest}.
     * service 层校验 amount / reason / 源单据状态 / 币种一致性.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('finance:adjustment:create')")
    public R<BillingAdjustment> create(@RequestBody CreateAdjustmentRequest body) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        return R.ok(adjustmentService.create(
            tenantId,
            body.sourceInvoiceId(),
            body.adjustmentType(),
            body.amount(),
            body.reason(),
            operatorId
        ));
    }

    /**
     * 提交调账单: DRAFT → PENDING_L1.
     */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('finance:adjustment:submit')")
    public R<BillingAdjustment> submit(@PathVariable Long id) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        return R.ok(adjustmentService.submit(tenantId, id, operatorId));
    }

    /**
     * 财务初审通过: PENDING_L1 → PENDING_L2. 请求体 {@code {"comment": "..."}} 可选.
     */
    @PostMapping("/{id}/l1-approve")
    @PreAuthorize("hasAuthority('finance:adjustment:l1_approve')")
    public R<BillingAdjustment> l1Approve(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, String> body) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        String comment = body == null ? null : body.get("comment");
        return R.ok(adjustmentService.l1Approve(tenantId, id, operatorId, comment));
    }

    /**
     * 财务初审驳回: PENDING_L1 → REJECTED. {@code comment} 必填 (非空 + 非空白),
     * 缺 / 空时 service 抛 {@code ADJUSTMENT_REJECT_REASON_REQUIRED} → 400.
     */
    @PostMapping("/{id}/l1-reject")
    @PreAuthorize("hasAuthority('finance:adjustment:l1_approve')")
    public R<BillingAdjustment> l1Reject(@PathVariable Long id,
                                         @RequestBody Map<String, String> body) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        String comment = body == null ? null : body.get("comment");
        return R.ok(adjustmentService.l1Reject(tenantId, id, operatorId, comment));
    }

    /**
     * 财务终审通过: PENDING_L2 → L2_APPROVED. 请求体 {@code {"comment": "..."}} 可选.
     *
     * <p>硬约束: 同一操作员不能同时做 L1 和 L2 — 由 service 层校验 (4.3.2 PR4).
     */
    @PostMapping("/{id}/l2-approve")
    @PreAuthorize("hasAuthority('finance:adjustment:l2_approve')")
    public R<BillingAdjustment> l2Approve(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, String> body) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        String comment = body == null ? null : body.get("comment");
        return R.ok(adjustmentService.l2Approve(tenantId, id, operatorId, comment));
    }

    /**
     * 财务终审驳回: PENDING_L2 → REJECTED. {@code comment} 必填 (非空 + 非空白),
     * 缺 / 空时 service 抛 {@code ADJUSTMENT_REJECT_REASON_REQUIRED} → 400.
     *
     * <p>硬约束: 同一操作员不能同时做 L1 和 L2 — 由 service 层校验 (4.3.2 PR4).
     */
    @PostMapping("/{id}/l2-reject")
    @PreAuthorize("hasAuthority('finance:adjustment:l2_approve')")
    public R<BillingAdjustment> l2Reject(@PathVariable Long id,
                                         @RequestBody Map<String, String> body) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        String comment = body == null ? null : body.get("comment");
        return R.ok(adjustmentService.l2Reject(tenantId, id, operatorId, comment));
    }

    /**
     * 财务经理手动确认过账: L2_APPROVED → POSTED. 无请求体.
     */
    @PostMapping("/{id}/mark-posted")
    @PreAuthorize("hasAuthority('finance:adjustment:l2_approve')")
    public R<BillingAdjustment> markPosted(@PathVariable Long id) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        return R.ok(adjustmentService.markPosted(tenantId, id, operatorId));
    }

    /** Request body for create — local DTO (java record). */
    public record CreateAdjustmentRequest(
        Long sourceInvoiceId,
        String adjustmentType,  // CREDIT or DEBIT
        BigDecimal amount,
        String reason
    ) {}
}