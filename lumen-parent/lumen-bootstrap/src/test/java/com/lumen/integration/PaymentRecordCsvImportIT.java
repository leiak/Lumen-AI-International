package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.PaymentRecordDtos.CreateRequest;
import com.lumen.finance.dto.PaymentRecordDtos.ImportResult;
import com.lumen.finance.entity.BillingPaymentRecord;
import com.lumen.finance.service.PaymentRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentRecordService 端到端集成测试 (Iteration 4.4 PR1-T1.8).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. 覆盖:
 * <ol>
 *   <li>{@code create} 单条录入 (source=MANUAL)</li>
 *   <li>{@code importCsv} 12 行 CSV: 10 行成功 (含去重后净增 10) + 2 行重复被
 *       UNIQUE KEY (tenant_id, period, direction, counterparty_id, payment_date, deleted)
 *       拦截为 skipped</li>
 *   <li>{@code list} 跨 create + importCsv 共有 11 行 (1 MANUAL + 10 CSV)</li>
 * </ol>
 *
 * <p>每个测试独占 tenantId (1_000_000+) 避开 lumen-cs (100_000+) /
 * BillingRuleActivationIT (200_000+) / BillingEventListenerIT (300_000+) /
 * BillingAccrualFinalizeIT (400_000+) / BillingInvoiceApprovalIT (500_000+) /
 * BillingInvoiceL2ApprovalIT + BillingRuleAuditIT (600_000+) /
 * BillingIdempotencyIT + BillingMultiCurrencyIT (700_000+) /
 * BillingAdjustmentCreditIT (800_000+) / BillingAdjustmentDebitIT (900_000+).
 */
@DisplayName("PaymentRecordCsvImport IT")
class PaymentRecordCsvImportIT extends IntegrationBase {

    @Autowired private PaymentRecordService service;

    /**
     * Counter-based unique tenantId. 起始 1_000_000L 避开所有现有 IT 占用段.
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(1_000_000L);
    private long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = TENANT_SEQ.incrementAndGet();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("PaymentRecordService: manual create + CSV import (10 success + 2 duplicate skipped) + list(11)")
    void createAndListAndImportCsv_fullFlow() {
        Long userId = 1L;  // IT 无 SecurityContext, 直接硬编码
        String p = "2026-10";

        // 1. single create (manual) — counterparty_id=200 / 2026-10-15, 不与 CSV 行冲突
        CreateRequest req = new CreateRequest();
        req.setPeriod(p);
        req.setDirection(FinanceConstants.DIR_RECEIVABLE);
        req.setCounterpartyId(200L);
        req.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        req.setCurrency(FinanceConstants.CURRENCY_CNY);
        req.setAmount(new BigDecimal("999.00"));
        req.setPaymentDate(LocalDate.of(2026, 10, 15));
        req.setSourceRef("BANK-MANUAL-001");
        BillingPaymentRecord created = service.create(tenantId, userId, req);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getSource()).isEqualTo(FinanceConstants.PAY_SOURCE_MANUAL);
        assertThat(created.getCreatedBy()).isEqualTo(userId);

        // 2. CSV import: 12 行, 其中 2 行 (counterparty_id=201/202 与首两行同)
        //    会被 UNIQUE KEY 拦截为 skipped
        String csv = "direction,counterparty_id,counterparty_type,currency,amount,payment_date,source_ref\n"
                   + "RECEIVABLE,201,CUSTOMER,CNY,100.00,2026-10-15,B001\n"
                   + "RECEIVABLE,202,CUSTOMER,CNY,200.00,2026-10-15,B002\n"
                   + "RECEIVABLE,203,CUSTOMER,CNY,300.00,2026-10-15,B003\n"
                   + "RECEIVABLE,204,CUSTOMER,CNY,400.00,2026-10-15,B004\n"
                   + "RECEIVABLE,205,CUSTOMER,CNY,500.00,2026-10-15,B005\n"
                   + "RECEIVABLE,201,CUSTOMER,CNY,100.00,2026-10-15,B001\n"   // dup of row 1
                   + "RECEIVABLE,202,CUSTOMER,CNY,200.00,2026-10-15,B002\n"   // dup of row 2
                   + "RECEIVABLE,206,CUSTOMER,CNY,600.00,2026-10-15,B006\n"
                   + "RECEIVABLE,207,CUSTOMER,CNY,700.00,2026-10-15,B007\n"
                   + "RECEIVABLE,208,CUSTOMER,CNY,800.00,2026-10-15,B008\n"
                   + "RECEIVABLE,209,CUSTOMER,CNY,900.00,2026-10-15,B009\n"
                   + "RECEIVABLE,210,CUSTOMER,CNY,1000.00,2026-10-15,B010\n";
        MultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv",
            csv.getBytes(StandardCharsets.UTF_8));

        ImportResult result = service.importCsv(tenantId, p, userId, file);

        assertThat(result.getTotalRows()).isEqualTo(12);
        assertThat(result.getSuccessCount()).isEqualTo(10);
        assertThat(result.getSkippedCount()).isEqualTo(2);
        assertThat(result.getErrors()).isEmpty();

        // 3. list: 1 manual + 10 imported = 11 条记录
        List<BillingPaymentRecord> records = service.list(tenantId, p,
            FinanceConstants.DIR_RECEIVABLE, 1, 50);
        assertThat(records).hasSize(11);
        // 校验 source 分布: 1 MANUAL + 10 CSV
        long manualCount = records.stream()
            .filter(r -> FinanceConstants.PAY_SOURCE_MANUAL.equals(r.getSource()))
            .count();
        long csvCount = records.stream()
            .filter(r -> FinanceConstants.PAY_SOURCE_CSV.equals(r.getSource()))
            .count();
        assertThat(manualCount).isEqualTo(1);
        assertThat(csvCount).isEqualTo(10);
    }
}