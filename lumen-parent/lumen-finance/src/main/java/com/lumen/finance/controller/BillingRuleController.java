package com.lumen.finance.controller;

import com.lumen.common.api.R;
import com.lumen.common.security.SecurityUtil;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.dto.BillingRuleDtos.CreateRequest;
import com.lumen.finance.dto.BillingRuleDtos.UpdateRequest;
import com.lumen.finance.dto.BillingRuleDtos.View;
import com.lumen.finance.service.BillingRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计费规则 REST 控制器 (Iteration 4.3.1, PR3-T3.5).
 *
 * <p>路径 {@code /api/v1/finance/rules}; 6 个端点 (CRUD + activate + disable).
 * 权限按动作细粒度: {@code finance:rule:view} (list/get) + {@code finance:rule:edit}
 * (create/update/activate/disable). 对应 V26/V27 RBAC seed.
 *
 * <p>tenantId 从 {@link TenantContext} 取 (controller 层职责);
 * operatorId 从 {@link SecurityUtil} 取 (由 {@code JwtAuthenticationFilter}
 * 设置的 {@code RbacUserDetails} 中 {@code userId}). service 仍接受显式参数,
 * 便于 batch / 跨租户后台任务调用.
 */
@RestController
@RequestMapping("/api/v1/finance/rules")
@RequiredArgsConstructor
public class BillingRuleController {

    private final BillingRuleService billingRuleService;

    @GetMapping
    @PreAuthorize("hasAuthority('finance:rule:view')")
    public R<List<View>> list(@RequestParam(required = false) String businessType,
                              @RequestParam(required = false) String direction,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false, defaultValue = "1") Integer page,
                              @RequestParam(required = false, defaultValue = "20") Integer size) {
        Long tenantId = TenantContext.require();
        return R.ok(billingRuleService.list(tenantId, businessType, direction, status, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('finance:rule:view')")
    public R<View> get(@PathVariable Long id) {
        Long tenantId = TenantContext.require();
        return R.ok(billingRuleService.get(tenantId, id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('finance:rule:edit')")
    public R<View> create(@Valid @RequestBody CreateRequest req) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        return R.ok(billingRuleService.create(tenantId, operatorId, req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('finance:rule:edit')")
    public R<View> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest req) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        return R.ok(billingRuleService.update(tenantId, id, operatorId, req));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('finance:rule:edit')")
    public R<View> activate(@PathVariable Long id) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        return R.ok(billingRuleService.activate(tenantId, id, operatorId));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('finance:rule:edit')")
    public R<View> disable(@PathVariable Long id) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        return R.ok(billingRuleService.disable(tenantId, id, operatorId));
    }
}