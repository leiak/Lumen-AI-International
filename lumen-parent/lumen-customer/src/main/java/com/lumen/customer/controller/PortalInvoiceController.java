package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.customer.web.PortalDownloads;
import com.lumen.customer.web.PortalIdentity;
import com.lumen.finance.dto.portal.MerchantDtos.InvoiceView;
import com.lumen.finance.dto.portal.MerchantPage;
import com.lumen.finance.service.MerchantBillingViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户门户发票 controller (Iteration 4.5C-2).
 *
 * <p>3 个端点 (路由前缀 {@code /api/customer/invoice}), 全部 {@code customer:invoice:view}
 * —— 该码 V33 就 seed 好了, 本轮没新增 (见 V40 注释里 uk_perm_tenant_code 的坑)。
 *
 * <p><b>路径顺序与 {@link PortalReconController} 同理</b>: {@code /export} 不会被
 * {@code /{id}} 抢走, 因为 Spring MVC 的模式比较器把字面量段排在模板段之上 ——
 * 这与声明顺序无关, 调换声明位置也不影响。
 */
@RestController
@RequestMapping("/api/customer/invoice")
@RequiredArgsConstructor
public class PortalInvoiceController {

    private final MerchantBillingViewService billingView;

    /**
     * GET / — 列表。
     *
     * <p><b>分页参数由商家自己给</b> (4.5C-2.1 缺口 #9): 以前 page / size 是写死的
     * {@code 1, 20} 字面量, 商家发票超过 20 条就再也翻不到后面的。clamp
     * (size&lt;=0→20, size&gt;200→200, page&lt;1→1) 在 finance 侧的 service 里做,
     * 这里<b>原样透传</b> —— controller 再 clamp 一次就多了一个可能与 finance
     * 口径不一致的副本。
     */
    @GetMapping
    @PreAuthorize("hasAuthority('customer:invoice:view')")
    public R<MerchantPage<InvoiceView>> list(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(billingView.listInvoices(
                PortalIdentity.requireTenantId(),
                PortalIdentity.requireCounterpartyId(),
                period, status, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:invoice:view')")
    public R<InvoiceView> detail(@PathVariable Long id) {
        return R.ok(billingView.getInvoice(
                PortalIdentity.requireTenantId(), PortalIdentity.requireCounterpartyId(), id));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('customer:invoice:view')")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String status) {
        byte[] body = billingView.exportInvoices(
                PortalIdentity.requireTenantId(), PortalIdentity.requireCounterpartyId(), period, status);
        return PortalDownloads.xlsx("发票.xlsx", body);
    }
}
