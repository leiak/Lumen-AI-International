package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.customer.web.PortalIdentity;
import com.lumen.finance.dto.portal.MerchantDtos.PaymentView;
import com.lumen.finance.dto.portal.MerchantPage;
import com.lumen.finance.service.MerchantBillingViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户门户付款记录 controller (Iteration 4.5C-2).
 *
 * <p>1 个端点 (路由前缀 {@code /api/customer/payment}), {@code customer:payment:view}
 * —— 该码是 V40 新 seed 的 169 号。
 *
 * <p><b>只有列表, 没有导出。</b> 商家看付款记录是为了核对"我付过没有", 逐条点开
 * 已经在列表里了 (字段不裁剪)。加导出会多一条 xlsx 生成路径和一份权限面的推敲,
 * YAGNI。
 */
@RestController
@RequestMapping("/api/customer/payment")
@RequiredArgsConstructor
public class PortalPaymentController {

    private final MerchantBillingViewService billingView;

    @GetMapping
    @PreAuthorize("hasAuthority('customer:payment:view')")
    public R<MerchantPage<PaymentView>> list(@RequestParam(required = false) String period) {
        return R.ok(billingView.listPayments(
                PortalIdentity.requireTenantId(),
                PortalIdentity.requireCounterpartyId(),
                period, 1, 20));
    }
}
