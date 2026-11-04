package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.customer.web.PortalIdentity;
import com.lumen.finance.dto.portal.MerchantDtos.AdjustmentView;
import com.lumen.finance.dto.portal.MerchantPage;
import com.lumen.finance.service.MerchantBillingViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户门户调账单 controller (Iteration 4.5C-2).
 *
 * <p>2 个端点 (路由前缀 {@code /api/customer/adjustment}), 全部 {@code customer:adjustment:view}
 * —— V40 seed 的 170 号。
 *
 * <p>没有"审批"端点。调账单审批是财务的动作, 商家这边只能看; 商家想改变金额时
 * 走的是对账异议 ({@link PortalReconController#dispute}), 不是直接改调账单。
 * 这一条要是漏了, 门户就等于把财务审批权开给了商家。
 */
@RestController
@RequestMapping("/api/customer/adjustment")
@RequiredArgsConstructor
public class PortalAdjustmentController {

    private final MerchantBillingViewService billingView;

    @GetMapping
    @PreAuthorize("hasAuthority('customer:adjustment:view')")
    public R<MerchantPage<AdjustmentView>> list(
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        return R.ok(billingView.listMyAdjustments(
                PortalIdentity.requireTenantId(),
                PortalIdentity.requireCounterpartyId(),
                page == null ? 1L : page, size == null ? 20L : size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:adjustment:view')")
    public R<AdjustmentView> detail(@PathVariable Long id) {
        return R.ok(billingView.getMyAdjustment(
                PortalIdentity.requireTenantId(), PortalIdentity.requireCounterpartyId(), id));
    }
}
