package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.customer.web.PortalDownloads;
import com.lumen.customer.web.PortalIdentity;
import com.lumen.finance.dto.portal.MerchantDtos.InvoiceView;
import com.lumen.finance.dto.portal.MerchantPage;
import com.lumen.finance.service.MerchantBillingViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>路径顺序与 {@link PortalReconController} 同理: {@code /export} 必须在 {@code /{id}} 之前声明。
 */
@Slf4j
@RestController
@RequestMapping("/api/customer/invoice")
@RequiredArgsConstructor
public class PortalInvoiceController {

    private final MerchantBillingViewService billingView;

    @GetMapping
    @PreAuthorize("hasAuthority('customer:invoice:view')")
    public R<MerchantPage<InvoiceView>> list(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String status) {
        return R.ok(billingView.listInvoices(
                PortalIdentity.requireTenantId(),
                PortalIdentity.requireCounterpartyId(),
                period, status, 1, 20));
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
