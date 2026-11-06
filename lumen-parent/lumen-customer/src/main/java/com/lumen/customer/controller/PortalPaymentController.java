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

    /**
     * GET / — 列表。
     *
     * <p><b>分页参数由商家自己给</b> (4.5C-2.1 缺口 #9): 以前 page / size 是写死的
     * {@code 1, 20} 字面量, 商家付款流水超过 20 条就再也翻不到后面的。
     * 本端点<b>没有导出</b> (见类注释的 YAGNI 说明), 所以分页是商家读全量流水的
     * <b>唯一</b>途径 —— 更不能写死。clamp (size&lt;=0→20, size&gt;200→200,
     * page&lt;1→1, page&gt;1000000→1000000) 在 finance 侧的 service 里做, 这里
     * <b>原样透传</b> —— controller 再 clamp 一次就多了一个可能与 finance 口径不一致
     * 的副本, 而且会把商家能翻到的页数悄悄截短。
     */
    @GetMapping
    @PreAuthorize("hasAuthority('customer:payment:view')")
    public R<MerchantPage<PaymentView>> list(
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(billingView.listPayments(
                PortalIdentity.requireTenantId(),
                PortalIdentity.requireCounterpartyId(),
                period, page, size));
    }
}
