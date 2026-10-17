package com.lumen.finance.controller;

import com.lumen.common.api.R;
import com.lumen.common.security.SecurityUtil;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.dto.BillingInvoiceDtos.View;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 应收/应付单据 REST 控制器 (Iteration 4.3.1, PR3-T3.5).
 *
 * <p>路径 {@code /api/v1/finance/invoices}; 5 个端点 (list + get + submit + l1-approve + l1-reject).
 * 权限按动作细粒度:
 * <ul>
 *   <li>{@code finance:invoice:view} — list + get</li>
 *   <li>{@code finance:invoice:submit} — submit (DRAFT → PENDING_L1)</li>
 *   <li>{@code finance:invoice:l1_approve} — l1-approve + l1-reject (4.3.1 仅 1 级审批)</li>
 * </ul>
 *
 * <p>对应 V26 RBAC seed. tenantId 从 {@link TenantContext} 取, operatorId 从 {@link SecurityUtil} 取.
 *
 * <p>l1Reject 的 {@code comment} 必填校验由 service 层 ({@code InvoiceService.l1Reject}) 完成
 * — 缺 / 空时抛 {@code FinanceException(INVOICE_REJECT_REASON_REQUIRED)} → 400.
 */
@RestController
@RequestMapping("/api/v1/finance/invoices")
@RequiredArgsConstructor
public class BillingInvoiceController {

    private final InvoiceService invoiceService;

    @GetMapping
    @PreAuthorize("hasAuthority('finance:invoice:view')")
    public R<List<View>> list(@RequestParam(required = false) String status,
                              @RequestParam(required = false, defaultValue = "1") Integer page,
                              @RequestParam(required = false, defaultValue = "20") Integer size) {
        Long tenantId = TenantContext.require();
        List<BillingInvoice> entities = invoiceService.list(tenantId, status, page, size);
        return R.ok(entities.stream().map(BillingInvoiceController::toView).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('finance:invoice:view')")
    public R<View> get(@PathVariable Long id) {
        Long tenantId = TenantContext.require();
        return R.ok(toView(invoiceService.get(tenantId, id)));
    }

    /**
     * 提交单据: DRAFT → PENDING_L1.
     */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('finance:invoice:submit')")
    public R<View> submit(@PathVariable Long id) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        return R.ok(toView(invoiceService.submit(tenantId, id, operatorId)));
    }

    /**
     * 财务初审通过: PENDING_L1 → L1_APPROVED.
     * 请求体 {@code {"comment": "..."}} 可选.
     */
    @PostMapping("/{id}/l1-approve")
    @PreAuthorize("hasAuthority('finance:invoice:l1_approve')")
    public R<View> l1Approve(@PathVariable Long id,
                             @RequestBody(required = false) Map<String, String> body) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        String comment = body == null ? null : body.get("comment");
        return R.ok(toView(invoiceService.l1Approve(tenantId, id, operatorId, comment)));
    }

    /**
     * 财务初审驳回: PENDING_L1 → REJECTED. {@code comment} 必填 (非空 + 非空白),
     * 缺 / 空时 service 抛 {@code INVOICE_REJECT_REASON_REQUIRED} → 400.
     */
    @PostMapping("/{id}/l1-reject")
    @PreAuthorize("hasAuthority('finance:invoice:l1_approve')")
    public R<View> l1Reject(@PathVariable Long id,
                            @RequestBody(required = false) Map<String, String> body) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        String comment = body == null ? null : body.get("comment");
        return R.ok(toView(invoiceService.l1Reject(tenantId, id, operatorId, comment)));
    }

    // ---------- mapper helper ----------

    private static View toView(BillingInvoice i) {
        if (i == null) return null;
        // 4.3.1 阶段 L2 字段全部为 null; entity 没有 l2_approver_id / l2_approved_at / l2_comment
        // 列 (4.3.2 PR2 才加), 这里固定填 null.
        return new View(
            i.getId(),
            i.getTenantId(),
            i.getInvoiceNo(),
            i.getDirection(),
            i.getCounterpartyId(),
            i.getCounterpartyType(),
            i.getBusinessType(),
            i.getSourceAccrualId(),
            i.getGrossAmount(),
            i.getTaxAmount(),
            i.getNetAmount(),
            i.getCurrency(),
            i.getStatus(),
            i.getRuleId(),
            i.getRuleVersion(),
            i.getSubmittedAt(),
            i.getL1ApproverId(),
            i.getL1ApprovedAt(),
            i.getL1Comment(),
            null,                          // l2ApproverId
            null,                          // l2ApprovedAt
            null,                          // l2Comment
            i.getErrorMsg(),               // errorMsg — V28 entity 字段 (PUSHED 失败留痕)
            i.getCreatedAt()
        );
    }
}