package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lumen.finance.dto.AdjustmentDtos;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.service.impl.BillingAdjustmentServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BillingAdjustmentServiceImpl} 锚点解析的可见性契约 (4.5C-2 Task 2, B3 写侧).
 *
 * <p>锚点 ({@code source_invoice_id}) 是商家门户"我的调账单"的查询键。锚点落在别家
 * 商家的发票上, 商家就会在门户里看到别人的调账单 —— 含金额、差异, 以及 reason 里
 * 明写的对手方 id。所以锚点必须感知 counterparty。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingAdjustmentServiceImpl — 锚点解析的归属隔离")
class BillingAdjustmentServiceImplTest {

    @Mock AdjustmentService adjustmentService;
    @Mock BillingInvoiceMapper invoiceMapper;

    @InjectMocks BillingAdjustmentServiceImpl service;

    private static final Long TENANT = 1L;
    private static final Long ME = 42L;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BillingInvoice.class);
    }

    private static BillingInvoice invoice(Long id, Long counterpartyId) {
        BillingInvoice i = new BillingInvoice();
        i.setId(id);
        i.setTenantId(TENANT);
        i.setCounterpartyId(counterpartyId);
        i.setDirection("RECEIVABLE");
        i.setStatus("L2_APPROVED");
        i.setCurrency("CNY");
        return i;
    }

    private static AdjustmentDtos.CreateRequest request(Long counterpartyId) {
        AdjustmentDtos.CreateRequest req = new AdjustmentDtos.CreateRequest();
        req.setAdjustmentType("DEBIT");
        req.setAmount(new BigDecimal("10.00"));
        req.setCurrency("CNY");
        req.setReason("对账差异自动生成: 期间 2026-09");
        req.setCounterpartyId(counterpartyId);
        return req;
    }

    @Test
    @DisplayName("给了 counterpartyId → 锚点查询必须带上 counterparty_id + direction, 不能只按租户挑")
    void resolveSourceInvoiceId_isCounterpartyAware() {
        when(invoiceMapper.selectList(any())).thenReturn(List.of(invoice(21L, ME)));
        when(adjustmentService.create(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new BillingAdjustment());

        service.create(TENANT, request(ME));

        ArgumentCaptor<LambdaQueryWrapper<BillingInvoice>> c = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(invoiceMapper).selectList(c.capture());
        String sql = c.getValue().getSqlSegment();
        assertThat(sql).contains("tenant_id", "counterparty_id", "direction");
        assertThat(c.getValue().getParamNameValuePairs().values()).contains(TENANT, ME, "RECEIVABLE");
    }

    @Test
    @DisplayName("counterparty 名下没有候选发票 → 退回租户级锚点 (不抛, 不破坏 4.4 月结流程)")
    void resolveSourceInvoiceId_fallsBackToTenantLevel() {
        // 收窄查询返回空, 租户级查询返回一张
        when(invoiceMapper.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<BillingInvoice> w = inv.getArgument(0);
            return w.getSqlSegment().contains("counterparty_id") ? List.of() : List.of(invoice(77L, 99L));
        });
        when(adjustmentService.create(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new BillingAdjustment());

        service.create(TENANT, request(ME));

        ArgumentCaptor<Long> anchor = ArgumentCaptor.forClass(Long.class);
        verify(adjustmentService).create(anyLong(), anchor.capture(), any(), any(), any(), any());
        // 退到了租户级那张
        assertThat(anchor.getValue()).isEqualTo(77L);
    }

    @Test
    @DisplayName("没给 counterpartyId → 保持旧行为, 不加 counterparty 过滤 (向后兼容)")
    void resolveSourceInvoiceId_withoutCounterpartyKeepsOldBehaviour() {
        when(invoiceMapper.selectList(any())).thenReturn(List.of(invoice(77L, 99L)));
        when(adjustmentService.create(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new BillingAdjustment());

        service.create(TENANT, request(null));

        ArgumentCaptor<LambdaQueryWrapper<BillingInvoice>> c = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(invoiceMapper).selectList(c.capture());
        assertThat(c.getValue().getSqlSegment()).doesNotContain("counterparty_id");
    }

    @Test
    @DisplayName("租户级也没有任何 L2_APPROVED 发票 → BAD_REQUEST (沿用 4.4 既有语义)")
    void resolveSourceInvoiceId_noCandidateThrowsBadRequest() {
        when(invoiceMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(TENANT, request(ME)))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("显式给了 sourceInvoiceId → 不做任何锚点查询")
    void explicitSourceInvoiceId_skipsAnchorResolution() {
        AdjustmentDtos.CreateRequest req = request(ME);
        req.setSourceInvoiceId(21L);
        when(adjustmentService.create(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new BillingAdjustment());

        service.create(TENANT, req);

        verify(invoiceMapper, never()).selectList(any());
    }
}