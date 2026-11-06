package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.security.CustomerContext;
import com.lumen.finance.dto.portal.MerchantDtos.InvoiceView;
import com.lumen.finance.dto.portal.MerchantDtos.PaymentView;
import com.lumen.finance.dto.portal.MerchantDtos.ReconView;
import com.lumen.finance.dto.portal.MerchantPage;
import com.lumen.finance.service.MerchantBillingViewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4.5C-2.1 — 商家门户列表端点的分页参数透传 (缺口 #9)。
 *
 * <p>这些 controller 以前把 page / size 写死成 1 / 20, 商家超过 20 条就再也
 * 看不到剩下的。finance 侧的 service 早就支持分页 —— 缺的只是 controller 这一层。
 *
 * <p><b>断言的是"参数原样透传", 不是"页码被 clamp"</b>: clamp
 * (size&lt;=0→20, size&gt;200→200, page&lt;1→1, page&gt;1000000→1000000) 在 finance
 * 侧的 service 里做, 门户这一层的职责只是把商家给的 page / size 送到那里, 不做
 * 二次加工 —— 真在 controller 里再 clamp 一次, 就多了一个可能与 finance 侧不一致
 * 的口径, 而且会把商家能翻到的页数悄悄截短 (本 task 要消灭的正是"只能看前 N 条")。
 *
 * <p>三类断言各管一件事, 缺一不可:
 * <ol>
 *   <li><b>原样透传</b> (正常值 + 越界值) —— 越界值那组是防"controller 侧也 clamp
 *       一下"的关键: 正常值全在 finance 的钳位区间内, 加了 clamp 也照样绿</li>
 *   <li><b>默认值</b> (MockMvc, 不传参数) —— {@code @RequestParam(defaultValue=...)}
 *       是 Spring MVC 层的, 直接调方法永远碰不到它</li>
 *   <li><b>返回值原样透传</b> —— 防"手滑把 service 的结果换成空页"</li>
 * </ol>
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

    // ---------------------------------------------------------------- 正常值透传

    @Test
    @DisplayName("recon: page / size 原样传给 service")
    void recon_passesPageAndSize() {
        when(billingView.listReconciliations(anyLong(), anyLong(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(3, 5));

        R<MerchantPage<ReconView>> r = reconController.list(null, 3L, 5L);

        verify(billingView).listReconciliations(TENANT, CUSTOMER, null, 3L, 5L);
        // 返回值也得是 service 给的那份 —— 手滑换成 MerchantPage.empty(1, 20) 也得红
        assertThat(r.getData().page()).isEqualTo(3);
        assertThat(r.getData().size()).isEqualTo(5);
    }

    @Test
    @DisplayName("invoice: page / size 原样传给 service")
    void invoice_passesPageAndSize() {
        when(billingView.listInvoices(anyLong(), anyLong(), isNull(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(2, 50));

        R<MerchantPage<InvoiceView>> r = invoiceController.list(null, null, 2L, 50L);

        verify(billingView).listInvoices(TENANT, CUSTOMER, null, null, 2L, 50L);
        assertThat(r.getData().page()).isEqualTo(2);
        assertThat(r.getData().size()).isEqualTo(50);
    }

    @Test
    @DisplayName("payment: page / size 原样传给 service")
    void payment_passesPageAndSize() {
        when(billingView.listPayments(anyLong(), anyLong(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(4, 10));

        R<MerchantPage<PaymentView>> r = paymentController.list(null, 4L, 10L);

        verify(billingView).listPayments(TENANT, CUSTOMER, null, 4L, 10L);
        assertThat(r.getData().page()).isEqualTo(4);
        assertThat(r.getData().size()).isEqualTo(10);
    }

    // ---------------------------------------------------------------- 越界值透传

    /**
     * 下面 3 条是<b>本测试类最要紧的部分</b>: 断言的是"越界值也原样透传"。
     *
     * <p>上面 3 条正常值用例 (3/5、2/50、4/10) 全在 finance 的钳位区间内 ——
     * 所以 controller 里加一道 clamp ({@code Math.max(page,1)} /
     * {@code Math.min(size,200)}) 那 3 条照样全绿。而那道 clamp 是真实危害:
     * 把 page 截到 100, 商家有 10,000 条付款流水时就永远看不到第 100 页之后 ——
     * 正是本 task 要消灭的"只能看前 N 条"的翻版。
     */
    @Test
    @DisplayName("recon: page=0 / size=500 也原样透传 —— clamp 归 finance 侧, controller 不做二次加工")
    void recon_doesNotClamp() {
        when(billingView.listReconciliations(anyLong(), anyLong(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(0, 500));

        reconController.list(null, 0L, 500L);

        // 关键: 断言"原样", 而不是 clamp 后的值
        verify(billingView).listReconciliations(TENANT, CUSTOMER, null, 0L, 500L);
    }

    @Test
    @DisplayName("invoice: page=0 / size=500 也原样透传 —— clamp 归 finance 侧, controller 不做二次加工")
    void invoice_doesNotClamp() {
        when(billingView.listInvoices(anyLong(), anyLong(), isNull(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(0, 500));

        invoiceController.list(null, null, 0L, 500L);

        verify(billingView).listInvoices(TENANT, CUSTOMER, null, null, 0L, 500L);
    }

    @Test
    @DisplayName("payment: page=0 / size=500 也原样透传 —— clamp 归 finance 侧, controller 不做二次加工")
    void payment_doesNotClamp() {
        when(billingView.listPayments(anyLong(), anyLong(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(0, 500));

        paymentController.list(null, 0L, 500L);

        verify(billingView).listPayments(TENANT, CUSTOMER, null, 0L, 500L);
    }

    // ---------------------------------------------------------------- 默认值

    /**
     * {@code @RequestParam(defaultValue = ...)} 是 <b>Spring MVC 层</b>的行为 ——
     * 直接调 controller 方法时那个值永远生效不了, 所以默认值只能靠 MockMvc 验。
     *
     * <p><b>为什么默认值要紧</b>: 前端 {@code portalBillingService.ts} 目前不传
     * page / size, 这个默认值就是商家当前唯一生效的每页行数。把它从 20 改成 50,
     * 商家门户首页每页从 20 行静默变成 50 行, 而前端分页器若硬编码 20 (Task 5
     * 落地时很容易发生) 就会<b>重复或漏掉行</b> —— 前后端口径分叉。
     *
     * <p>⚠️ standaloneSetup 下 {@code @PreAuthorize} <b>不生效</b> —— 方法级鉴权
     * 没有 Security 的 AOP 代理, 所以这个测试<b>不能</b>拿来断言鉴权。鉴权由
     * {@code PortalBillingControllerSecurityTest} 负责 (那里锁的是注解字面量)。
     */
    @Test
    @DisplayName("recon: 不传 page / size → service 收到 1 / 20 (默认值锁死, 改了这里就红)")
    void recon_absentPagingParamsUseDefaultOneTwenty() throws Exception {
        when(billingView.listReconciliations(anyLong(), anyLong(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(1, 20));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PortalReconController(billingView)).build();

        mockMvc.perform(get("/api/customer/recon")).andExpect(status().isOk());

        verify(billingView).listReconciliations(anyLong(), anyLong(), isNull(), eq(1L), eq(20L));
    }

    @Test
    @DisplayName("invoice: 不传 page / size → service 收到 1 / 20 (默认值锁死, 改了这里就红)")
    void invoice_absentPagingParamsUseDefaultOneTwenty() throws Exception {
        when(billingView.listInvoices(anyLong(), anyLong(), isNull(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(1, 20));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PortalInvoiceController(billingView)).build();

        mockMvc.perform(get("/api/customer/invoice")).andExpect(status().isOk());

        verify(billingView).listInvoices(anyLong(), anyLong(), isNull(), isNull(), eq(1L), eq(20L));
    }

    @Test
    @DisplayName("payment: 不传 page / size → service 收到 1 / 20 (默认值锁死, 改了这里就红)")
    void payment_absentPagingParamsUseDefaultOneTwenty() throws Exception {
        when(billingView.listPayments(anyLong(), anyLong(), isNull(), anyLong(), anyLong()))
                .thenReturn(MerchantPage.empty(1, 20));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PortalPaymentController(billingView)).build();

        mockMvc.perform(get("/api/customer/payment")).andExpect(status().isOk());

        verify(billingView).listPayments(anyLong(), anyLong(), isNull(), eq(1L), eq(20L));
    }
}
