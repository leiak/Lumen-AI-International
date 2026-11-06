package com.lumen.customer.controller;

import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.security.CustomerContext;
import com.lumen.finance.dto.portal.MerchantPage;
import com.lumen.finance.service.MerchantBillingViewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 4.5C-2.1 — 商家门户列表端点的分页参数透传 (缺口 #9)。
 *
 * <p>这些 controller 以前把 page / size 写死成 1 / 20, 商家超过 20 条就再也
 * 看不到剩下的。finance 侧的 service 早就支持分页 —— 缺的只是 controller 这一层。
 *
 * <p><b>断言的是"参数原样透传", 不是"页码被 clamp"</b>: clamp
 * (size&lt;=0→20, size&gt;200→200, page&lt;1→1) 在 finance 侧的 service 里做,
 * 门户这一层的职责只是把商家给的 page / size 送到那里, 不做二次加工 ——
 * 真在 controller 里再 clamp 一次, 就多了一个可能与 finance 侧不一致的口径。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PortalBilling 列表端点 — 分页参数透传")
class PortalBillingPaginationTest {

    private static final Long TENANT = 11L;
    private static final Long CUSTOMER = 22L;

    @Mock
    private MerchantBillingViewService billingView;

    private PortalReconController reconController;
    private PortalInvoiceController invoiceController;
    private PortalPaymentController paymentController;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        CustomerContext.set(1L, CUSTOMER);
        reconController = new PortalReconController(billingView);
        invoiceController = new PortalInvoiceController(billingView);
        paymentController = new PortalPaymentController(billingView);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CustomerContext.clear();
    }

    @Test
    @DisplayName("recon: page / size 原样传给 service")
    void recon_passesPageAndSize() {
        when(billingView.listReconciliations(anyLong(), anyLong(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(3, 5));

        reconController.list(null, 3L, 5L);

        verify(billingView).listReconciliations(TENANT, CUSTOMER, null, 3L, 5L);
    }

    @Test
    @DisplayName("invoice: page / size 原样传给 service")
    void invoice_passesPageAndSize() {
        when(billingView.listInvoices(anyLong(), anyLong(), isNull(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(2, 50));

        invoiceController.list(null, null, 2L, 50L);

        verify(billingView).listInvoices(TENANT, CUSTOMER, null, null, 2L, 50L);
    }

    @Test
    @DisplayName("payment: page / size 原样传给 service")
    void payment_passesPageAndSize() {
        when(billingView.listPayments(anyLong(), anyLong(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(4, 10));

        paymentController.list(null, 4L, 10L);

        verify(billingView).listPayments(TENANT, CUSTOMER, null, 4L, 10L);
    }
}
