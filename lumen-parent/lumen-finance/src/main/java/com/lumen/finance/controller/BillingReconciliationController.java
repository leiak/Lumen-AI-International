package com.lumen.finance.controller;

import com.lumen.common.api.R;
import com.lumen.common.security.SecurityUtil;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.dto.ReconciliationDtos.ResolveRequest;
import com.lumen.finance.dto.ReconciliationDtos.RunRequest;
import com.lumen.finance.dto.ReconciliationDtos.WaiveRequest;
import com.lumen.finance.entity.BillingReconciliation;
import com.lumen.finance.service.ReconciliationService;
import jakarta.validation.Valid;
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

/**
 * 对账 (reconciliation + 自动调账) REST 控制器 (Iteration 4.4, Task 2.4).
 *
 * <p>路径 {@code /api/v1/finance/reconciliations}; 4 个端点
 * (run + list + /{id}/resolve + /{id}/waive).
 *
 * <p>权限按动作细粒度 (V30 RBAC seed, 4.4 PR2-extension 引入):
 * <ul>
 *   <li>{@code finance:reconciliation:run}     — 触发对账 (run)</li>
 *   <li>{@code finance:reconciliation:view}     — 列出对账记录 (list)</li>
 *   <li>{@code finance:reconciliation:resolve}  — 标记对账已解决 ({@code POST /{id}/resolve})</li>
 *   <li>{@code finance:reconciliation:waive}    — 豁免对账差异 ({@code POST /{id}/waive})</li>
 * </ul>
 *
 * <p>对应 {@link ReconciliationService}. tenantId 从 {@link TenantContext} 取,
 * operatorId 从 {@link SecurityUtil} 取. 期间参数 {@code period} 必须为 {@code YYYY-MM},
 * 校验由 {@link RunRequest} 的 {@code @Pattern} 完成 (service 层不再重复校验).
 */
@RestController
@RequestMapping("/api/v1/finance/reconciliations")
@RequiredArgsConstructor
public class BillingReconciliationController {

    private final ReconciliationService reconciliationService;

    /**
     * 触发对账: 对期间 {@code period} (YYYY-MM) 比较 L2_APPROVED 应收/应付 vs. 实收/实付,
     * 每条差异自动生成 DRAFT 状态调账单并落 {@code t_reconciliation} 行.
     * service 层幂等: 已有 ADJUSTMENT_CREATED 的 (period, direction, counterpartyId, currency)
     * 元组会被跳过. 返回 {@code R<Void>} — 调用方后续应 {@code GET /list} 取最新行.
     */
    @PostMapping("/run")
    @PreAuthorize("hasAuthority('finance:reconciliation:run')")
    public R<Void> run(@Valid @RequestBody RunRequest req) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        reconciliationService.runReconciliation(tenantId, req.getPeriod(), req.getCurrency(), operatorId);
        return R.ok(null);
    }

    /**
     * 列出指定期间的对账记录 (可选 direction / status 过滤). 全表不分页,
     * 按 (period, direction, counterpartyId) 升序.
     */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('finance:reconciliation:view')")
    public R<List<BillingReconciliation>> list(
        @RequestParam String period,
        @RequestParam(required = false) String direction,
        @RequestParam(required = false) String status
    ) {
        Long tenantId = TenantContext.require();
        return R.ok(reconciliationService.listByPeriod(tenantId, period, direction, status));
    }

    /**
     * 标记对账为已解决 ({@code RESOLVED}), 可选附 resolution note.
     * 幂等: 已是 RESOLVED 时直接返回当前行. 行备注追加策略: 原备注 + {@code " | resolve:<note>"},
     * 不覆盖已有 waive 原因.
     */
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('finance:reconciliation:resolve')")
    public R<BillingReconciliation> resolve(@PathVariable Long id,
                                            @Valid @RequestBody(required = false) ResolveRequest body) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        String note = body == null ? null : body.getResolutionNote();
        return R.ok(reconciliationService.resolveReconciliation(tenantId, id, note, operatorId));
    }

    /**
     * 豁免对账差异 (标记 {@code WAIVED}). {@code waiveReason} 至少 10 字符
     * (与 {@code FinanceConstants.ADJUSTMENT_REASON_MIN_LEN} 对齐), service 层硬校验.
     */
    @PostMapping("/{id}/waive")
    @PreAuthorize("hasAuthority('finance:reconciliation:waive')")
    public R<BillingReconciliation> waive(@PathVariable Long id,
                                          @Valid @RequestBody WaiveRequest body) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        return R.ok(reconciliationService.waiveReconciliation(tenantId, id, body.getWaiveReason(), operatorId));
    }
}
