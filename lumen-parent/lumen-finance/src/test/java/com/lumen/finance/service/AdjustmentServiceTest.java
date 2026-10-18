package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingAdjustmentMapper;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.service.impl.AdjustmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdjustmentService 单元测试 (Iteration 4.3.2, PR4-T4.1).
 *
 * <p>纯 Mockito 测试，不启 Spring。所有依赖 (mapper + stateMachine + exchangeRateService +
 * taxRateService) 都 mock 掉.
 *
 * <p>覆盖 10 个场景 (PR4 spec 锁定的 5 个 + 5 个补充):
 * <ul>
 *   <li>create: 继承源单据 currency + 默认 DRAFT 状态</li>
 *   <li>create: amount <= 0 → ADJUSTMENT_AMOUNT_INVALID</li>
 *   <li>create: reason < 10 字符 → ADJUSTMENT_REASON_REQUIRED</li>
 *   <li>create: gross=113 CNY (rate 0.13) → tax=13.0000 net=100.0000</li>
 *   <li>create: USD 源单据汇率 7.1234 → adjustment fxRate=7.1234</li>
 *   <li>create: 源单据非 L2_APPROVED → ADJUSTMENT_SOURCE_NOT_APPROVED</li>
 *   <li>create: 源单据跨租户 → INVOICE_NOT_FOUND</li>
 *   <li>create: adjustmentType 非法 → EXPRESSION_INVALID</li>
 *   <li>get: 不存在 / 跨租户 → ADJUSTMENT_NOT_FOUND</li>
 *   <li>list: 透传 mapper.selectPage</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdjustmentService 单元测试")
class AdjustmentServiceTest {

    @Mock private BillingAdjustmentMapper adjustmentMapper;
    @Mock private BillingInvoiceMapper invoiceMapper;
    @Mock private StateMachineEngine stateMachine;
    @Mock private ExchangeRateService exchangeRateService;
    @Mock private TaxRateService taxRateService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AdjustmentServiceImpl service;

    private static final Long TENANT = 1L;
    private static final Long SOURCE_INVOICE_ID = 100L;
    private static final Long OPERATOR = 1001L;

    private BillingInvoice sourceInvoice;

    @BeforeEach
    void setUp() {
        service = new AdjustmentServiceImpl(
            adjustmentMapper, invoiceMapper, stateMachine,
            exchangeRateService, taxRateService, eventPublisher);

        // 默认 source invoice: CNY, L2_APPROVED, fx_rate=1.0
        sourceInvoice = new BillingInvoice();
        sourceInvoice.setId(SOURCE_INVOICE_ID);
        sourceInvoice.setTenantId(TENANT);
        sourceInvoice.setInvoiceNo("AR-" + TENANT + "-" + SOURCE_INVOICE_ID);
        sourceInvoice.setCurrency(FinanceConstants.CURRENCY_CNY);
        sourceInvoice.setStatus(FinanceConstants.INV_STATUS_L2_APPROVED);
        sourceInvoice.setFxRateAtCreation(new BigDecimal("1.0"));

        // 默认税率: 13% (CNY)
        when(taxRateService.getActiveRate(any(String.class), any())).thenReturn(new BigDecimal("0.1300"));
        // 默认 invoice mapper: 返回默认 sourceInvoice
        when(invoiceMapper.selectById(SOURCE_INVOICE_ID)).thenReturn(sourceInvoice);
        // 默认 exchangeRate (虽然 create 当前不直接调, 但保险起见)
        when(exchangeRateService.getRate(any(), any(), any())).thenReturn(new BigDecimal("1.0"));
    }

    // ---------- 1. create: 继承源单据 currency + 默认 DRAFT ----------

    @Test
    @DisplayName("create: 继承源单据 currency (CNY) + 默认状态 DRAFT")
    void create_usesSourceInvoiceCurrency() {
        // arrange: insert 回填 id
        when(adjustmentMapper.insert(any(BillingAdjustment.class))).thenAnswer(inv -> {
            BillingAdjustment a = inv.getArgument(0);
            a.setId(999L);
            return 1;
        });

        // act
        BillingAdjustment adj = service.create(TENANT, SOURCE_INVOICE_ID, FinanceConstants.ADJ_TYPE_CREDIT,
            new BigDecimal("100.00"), "Reason 1234567890", OPERATOR);

        // assert: currency 继承源单据
        assertEquals(FinanceConstants.CURRENCY_CNY, adj.getCurrency());
        assertEquals(FinanceConstants.ADJ_STATUS_DRAFT, adj.getStatus());

        // assert: adjustmentNo 不为空, 以 ADJ- 开头
        assertThat(adj.getAdjustmentNo()).startsWith("ADJ-").contains("-" + TENANT + "-");

        // assert: insert 一次, AdjustmentType=CREDIT, sourceInvoiceId 透传, grossAmount=amount
        ArgumentCaptor<BillingAdjustment> cap = ArgumentCaptor.forClass(BillingAdjustment.class);
        verify(adjustmentMapper, times(1)).insert(cap.capture());
        BillingAdjustment inserted = cap.getValue();
        assertEquals(FinanceConstants.ADJ_TYPE_CREDIT, inserted.getAdjustmentType());
        assertEquals(SOURCE_INVOICE_ID, inserted.getSourceInvoiceId());
        assertThat(inserted.getGrossAmount()).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertEquals(FinanceConstants.ADJ_STATUS_DRAFT, inserted.getStatus());

        // assert: not calling state machine or update yet
        verify(adjustmentMapper, never()).updateById(any(BillingAdjustment.class));
        verify(stateMachine, never()).assertTransition(any(), any(), any());
    }

    // ---------- 2. create: amount <= 0 ----------

    @Test
    @DisplayName("create: amount <= 0 → ADJUSTMENT_AMOUNT_INVALID, 不入库")
    void create_amountNegative_throws() {
        assertThatThrownBy(() -> service.create(TENANT, SOURCE_INVOICE_ID, FinanceConstants.ADJ_TYPE_CREDIT,
            new BigDecimal("-10.00"), "Reason 1234567890", OPERATOR))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ADJUSTMENT_AMOUNT_INVALID);

        verify(adjustmentMapper, never()).insert(any(BillingAdjustment.class));
        verify(invoiceMapper, never()).selectById(any());
    }

    // ---------- 3. create: reason 长度 < 10 ----------

    @Test
    @DisplayName("create: reason 太短 → ADJUSTMENT_REASON_REQUIRED, 不入库")
    void create_reasonTooShort_throws() {
        assertThatThrownBy(() -> service.create(TENANT, SOURCE_INVOICE_ID, FinanceConstants.ADJ_TYPE_CREDIT,
            new BigDecimal("10.00"), "short", OPERATOR))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ADJUSTMENT_REASON_REQUIRED);

        verify(adjustmentMapper, never()).insert(any(BillingAdjustment.class));
        verify(invoiceMapper, never()).selectById(any());
    }

    // ---------- 4. create: 税额与净额计算 ----------

    @Test
    @DisplayName("create: gross=113 CNY @ 13% → tax=13.0000 net=100.0000")
    void create_calculatesTaxAndNet() {
        // arrange: insert 回填 id
        when(adjustmentMapper.insert(any(BillingAdjustment.class))).thenAnswer(inv -> {
            BillingAdjustment a = inv.getArgument(0);
            a.setId(999L);
            return 1;
        });

        // act: gross=113 含税, tax = 113 * 0.13 / 1.13 ≈ 13.0000, net = 100.0000
        BillingAdjustment adj = service.create(TENANT, SOURCE_INVOICE_ID, FinanceConstants.ADJ_TYPE_CREDIT,
            new BigDecimal("113.00"), "Reason 1234567890", OPERATOR);

        // assert: tax_amount, net_amount 落库即正确
        assertThat(adj.getTaxAmount()).isEqualByComparingTo(new BigDecimal("13.0000"));
        assertThat(adj.getNetAmount()).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(adj.getGrossAmount()).isEqualByComparingTo(new BigDecimal("113.0000"));

        // assert: taxRate 字段也写入 (审计可追溯)
        assertThat(adj.getTaxRate()).isEqualByComparingTo(new BigDecimal("0.1300"));
    }

    // ---------- 5. create: 汇率继承源单据 (USD 案例) ----------

    @Test
    @DisplayName("create: USD 源单据 fx_rate=7.1234 → adjustment 继承汇率")
    void create_usesSourceInvoiceFxRate() {
        // arrange: 改成 USD 源单据, fx_rate=7.1234
        sourceInvoice.setCurrency("USD");
        sourceInvoice.setFxRateAtCreation(new BigDecimal("7.1234"));
        // USD 税率 0%
        when(taxRateService.getActiveRate(any(String.class), any())).thenReturn(BigDecimal.ZERO);

        when(adjustmentMapper.insert(any(BillingAdjustment.class))).thenAnswer(inv -> {
            BillingAdjustment a = inv.getArgument(0);
            a.setId(999L);
            return 1;
        });

        // act
        BillingAdjustment adj = service.create(TENANT, SOURCE_INVOICE_ID, FinanceConstants.ADJ_TYPE_CREDIT,
            new BigDecimal("100.00"), "Reason 1234567890", OPERATOR);

        // assert: USD + fxRate=7.1234 (继承自源单据), 税率 0% → tax=0, net=100
        assertEquals("USD", adj.getCurrency());
        assertThat(adj.getFxRateAtCreation()).isEqualByComparingTo(new BigDecimal("7.1234"));
        assertThat(adj.getTaxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(adj.getNetAmount()).isEqualByComparingTo(new BigDecimal("100.0000"));
    }

    // ---------- 6. create: 源单据状态不是 L2_APPROVED → ADJUSTMENT_SOURCE_NOT_APPROVED ----------

    @Test
    @DisplayName("create: 源单据非 L2_APPROVED → ADJUSTMENT_SOURCE_NOT_APPROVED")
    void create_sourceInvoiceNotL2Approved_throws() {
        // arrange: 改成 PENDING_L1 源单据
        sourceInvoice.setStatus(FinanceConstants.INV_STATUS_PENDING_L1);

        assertThatThrownBy(() -> service.create(TENANT, SOURCE_INVOICE_ID, FinanceConstants.ADJ_TYPE_CREDIT,
            new BigDecimal("100.00"), "Reason 1234567890", OPERATOR))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ADJUSTMENT_SOURCE_NOT_APPROVED);

        verify(adjustmentMapper, never()).insert(any(BillingAdjustment.class));
    }

    // ---------- 7. create: 源单据跨租户 → INVOICE_NOT_FOUND ----------

    @Test
    @DisplayName("create: 源单据跨租户 → INVOICE_NOT_FOUND")
    void create_sourceInvoiceDifferentTenant_throws() {
        // arrange: 源单据属于别的租户
        sourceInvoice.setTenantId(9999L);

        assertThatThrownBy(() -> service.create(TENANT, SOURCE_INVOICE_ID, FinanceConstants.ADJ_TYPE_CREDIT,
            new BigDecimal("100.00"), "Reason 1234567890", OPERATOR))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVOICE_NOT_FOUND);

        verify(adjustmentMapper, never()).insert(any(BillingAdjustment.class));
    }

    // ---------- 8. create: adjustmentType 非法 → EXPRESSION_INVALID ----------

    @Test
    @DisplayName("create: adjustmentType 不是 CREDIT/DEBIT → EXPRESSION_INVALID")
    void create_invalidAdjustmentType_throws() {
        assertThatThrownBy(() -> service.create(TENANT, SOURCE_INVOICE_ID, "FOO",
            new BigDecimal("100.00"), "Reason 1234567890", OPERATOR))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.EXPRESSION_INVALID);

        verify(adjustmentMapper, never()).insert(any(BillingAdjustment.class));
        verify(invoiceMapper, never()).selectById(any());
    }

    // ---------- 9. list: 透传 mapper.selectPage ----------

    @Test
    @DisplayName("list: 过滤条件透传 mapper.selectPage (无 status)")
    @SuppressWarnings("unchecked")
    void list_noStatus_callsMapper() {
        when(adjustmentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
            .thenReturn(new Page<BillingAdjustment>());

        var rows = service.list(TENANT, null, 1, 20);

        assertThat(rows).isEmpty();

        verify(adjustmentMapper, times(1)).selectPage(
            argThat((Page<BillingAdjustment> p) -> p.getCurrent() == 1 && p.getSize() == 20),
            any(LambdaQueryWrapper.class));
    }

    // ---------- 10. get: 不存在 / 跨租户 → ADJUSTMENT_NOT_FOUND ----------

    @Test
    @DisplayName("get: 不存在 / 跨租户 → ADJUSTMENT_NOT_FOUND")
    void get_notFound_throwsAdjustmentNotFound() {
        // 不存在
        when(adjustmentMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.get(TENANT, 999L))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ADJUSTMENT_NOT_FOUND);

        // 跨租户
        BillingAdjustment otherTenant = new BillingAdjustment();
        otherTenant.setId(100L);
        otherTenant.setTenantId(9999L);
        otherTenant.setStatus(FinanceConstants.ADJ_STATUS_DRAFT);
        when(adjustmentMapper.selectById(100L)).thenReturn(otherTenant);
        assertThatThrownBy(() -> service.get(TENANT, 100L))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ADJUSTMENT_NOT_FOUND);
    }
}
