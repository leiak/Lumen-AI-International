package com.lumen.finance.service;

import com.lumen.finance.dto.AdjustmentDtos;
import com.lumen.finance.dto.ReconciliationDtos.Result;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.entity.BillingPaymentRecord;
import com.lumen.finance.entity.BillingReconciliation;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingAdjustmentMapper;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.mapper.BillingPaymentRecordMapper;
import com.lumen.finance.mapper.BillingReconciliationMapper;
import com.lumen.finance.service.impl.ReconciliationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock private BillingReconciliationMapper reconMapper;
    @Mock private BillingInvoiceMapper invoiceMapper;
    @Mock private BillingPaymentRecordMapper paymentMapper;
    @Mock private BillingAdjustmentMapper adjustmentMapper;
    @Mock private BillingAdjustmentService billingAdjustmentService;
    @InjectMocks private ReconciliationServiceImpl service;

    // ---------- runReconciliation ----------

    @Test
    void runReconciliation_zeroVariance_createsNoAdjustment() {
        when(invoiceMapper.selectList(any())).thenReturn(List.of());
        when(paymentMapper.selectList(any())).thenReturn(List.of());

        Result result = service.runReconciliation(1L, "2026-10");

        assertEquals(0, result.getVarianceCount());
        assertEquals(0, result.getTotalChecked());
        assertTrue(result.getAutoAdjustmentIds().isEmpty());
        verify(billingAdjustmentService, never()).create(any(), any());
    }

    @Test
    void runReconciliation_variancePositive_createsDebit() {
        BillingInvoice inv = new BillingInvoice();
        inv.setTenantId(1L);
        inv.setCounterpartyId(100L);
        inv.setCounterpartyType("CUSTOMER");
        inv.setDirection("RECEIVABLE");
        inv.setCurrency("CNY");
        inv.setNetAmount(new BigDecimal("1000"));
        inv.setStatus("L2_APPROVED");
        BillingPaymentRecord pay = BillingPaymentRecord.builder()
            .tenantId(1L).counterpartyId(100L).counterpartyType("CUSTOMER")
            .direction("RECEIVABLE").currency("CNY").amount(new BigDecimal("800"))
            .period("2026-10").paymentDate(LocalDate.now()).build();
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        when(paymentMapper.selectList(any())).thenReturn(List.of(pay));
        when(reconMapper.selectOne(any())).thenReturn(null);
        when(billingAdjustmentService.create(anyLong(), any())).thenReturn(BillingAdjustment.builder()
            .id(555L).status("DRAFT").build());

        Result result = service.runReconciliation(1L, "2026-10");

        assertEquals(1, result.getVarianceCount());
        assertEquals(1, result.getAutoAdjustmentIds().size());
        assertEquals("555", result.getAutoAdjustmentIds().get(0));

        // 验证 create 调用: variance > 0 → DEBIT (应收 > 实收 → 补收)
        ArgumentCaptor<AdjustmentDtos.CreateRequest> captor =
            ArgumentCaptor.forClass(AdjustmentDtos.CreateRequest.class);
        verify(billingAdjustmentService).create(eq(1L), captor.capture());
        AdjustmentDtos.CreateRequest req = captor.getValue();
        assertEquals("DEBIT", req.getAdjustmentType());
        assertEquals(0, new BigDecimal("200").compareTo(req.getAmount()));
        assertEquals("CNY", req.getCurrency());
    }

    @Test
    void runReconciliation_varianceNegative_createsCredit() {
        BillingInvoice inv = new BillingInvoice();
        inv.setTenantId(1L);
        inv.setCounterpartyId(100L);
        inv.setCounterpartyType("CUSTOMER");
        inv.setDirection("RECEIVABLE");
        inv.setCurrency("CNY");
        inv.setNetAmount(new BigDecimal("500"));
        inv.setStatus("L2_APPROVED");
        BillingPaymentRecord pay = BillingPaymentRecord.builder()
            .tenantId(1L).counterpartyId(100L).counterpartyType("CUSTOMER")
            .direction("RECEIVABLE").currency("CNY").amount(new BigDecimal("800"))
            .period("2026-10").paymentDate(LocalDate.now()).build();
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        when(paymentMapper.selectList(any())).thenReturn(List.of(pay));
        when(reconMapper.selectOne(any())).thenReturn(null);
        when(billingAdjustmentService.create(anyLong(), any())).thenReturn(BillingAdjustment.builder()
            .id(556L).status("DRAFT").build());

        Result result = service.runReconciliation(1L, "2026-10");

        assertEquals(1, result.getVarianceCount());
        // 验证: variance < 0 → CREDIT (应收 < 实收 → 退款)
        ArgumentCaptor<AdjustmentDtos.CreateRequest> captor =
            ArgumentCaptor.forClass(AdjustmentDtos.CreateRequest.class);
        verify(billingAdjustmentService).create(eq(1L), captor.capture());
        AdjustmentDtos.CreateRequest req = captor.getValue();
        assertEquals("CREDIT", req.getAdjustmentType());
        assertEquals(0, new BigDecimal("300").compareTo(req.getAmount()));
    }

    @Test
    void runReconciliation_idempotent_skipsExistingRecon() {
        BillingInvoice inv = new BillingInvoice();
        inv.setTenantId(1L);
        inv.setCounterpartyId(100L);
        inv.setCounterpartyType("CUSTOMER");
        inv.setDirection("RECEIVABLE");
        inv.setCurrency("CNY");
        inv.setNetAmount(new BigDecimal("1000"));
        inv.setStatus("L2_APPROVED");
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        when(paymentMapper.selectList(any())).thenReturn(List.of());
        when(reconMapper.selectOne(any())).thenReturn(BillingReconciliation.builder()
            .id(1L).reconciliationStatus("ADJUSTMENT_CREATED").build());

        Result result = service.runReconciliation(1L, "2026-10");

        assertEquals(0, result.getVarianceCount());
        verify(billingAdjustmentService, never()).create(any(), any());
    }

    // ---------- waive ----------

    @Test
    void waive_tooShortReason_throwsBadRequest() {
        // reason 长度校验在 get() 之前, selectById 不会被调用
        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.waive(1L, 1L, "short"));
        assertEquals(FinanceErrorCode.BAD_REQUEST.getCode(), ex.getErrorCode().getCode());
    }
}