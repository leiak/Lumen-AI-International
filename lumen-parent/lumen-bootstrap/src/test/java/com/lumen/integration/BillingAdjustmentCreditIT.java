package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingRuleDtos.CreateRequest;
import com.lumen.finance.dto.BillingRuleDtos.CreateRuleItem;
import com.lumen.finance.dto.BillingRuleDtos.View;
import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingAccrualMapper;
import com.lumen.finance.mapper.BillingAdjustmentMapper;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.service.AccrualService;
import com.lumen.finance.service.AdjustmentService;
import com.lumen.finance.service.BillingRuleService;
import com.lumen.finance.service.InvoiceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdjustmentService 调账单 (CREDIT note) 端到端集成测试 (Iteration 4.3.2, PR4-T4.3).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. 覆盖:
 * <ol>
 *   <li>完整 L1 + L2 + 财务过账 状态机路径: create (DRAFT) → submit (PENDING_L1) →
 *       l1Approve (PENDING_L2) → l2Approve (L2_APPROVED) → markPosted (POSTED),
 *       校验源单据 (L2_APPROVED) 状态保持不变.</li>
 *   <li>跨币种 (EUR 源单据) 调账 → {@code ADJUSTMENT_CURRENCY_MISMATCH}.
 *       4.3.2 仅支持 CNY / USD, 其它币种直接拒绝.</li>
 * </ol>
 *
 * <p>源 L2_APPROVED 单据通过 BillingInvoiceApprovalIT 同模式构造:
 * 建 ACTIVE rule → OPEN accrual → finalize (生成 DRAFT invoice) → submit → l1Approve → l2Approve.
 *
 * <p>每个测试用独占 tenantId (800_000+) 避开 BillingMultiCurrencyIT (700_000+) /
 * BillingInvoiceL2ApprovalIT (600_000+) / BillingInvoiceApprovalIT (500_000+) /
 * BillingAccrualFinalizeIT (400_000+) 占用段. 末尾清理 TenantContext.
 */
@DisplayName("BillingAdjustmentCredit IT")
class BillingAdjustmentCreditIT extends IntegrationBase {

    @Autowired private InvoiceService invoiceService;
    @Autowired private AccrualService accrualService;
    @Autowired private BillingRuleService billingRuleService;
    @Autowired private AdjustmentService adjustmentService;
    @Autowired private BillingAccrualMapper billingAccrualMapper;
    @Autowired private BillingInvoiceMapper billingInvoiceMapper;
    @Autowired private BillingAdjustmentMapper billingAdjustmentMapper;

    /**
     * Counter-based unique tenantId generator. 起始 800_000L 避开
     * BillingMultiCurrencyIT (700_000+) / BillingInvoiceL2ApprovalIT (600_000+) 等占用段.
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(800_000L);
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
    @DisplayName("AdjustmentService (CREDIT): create → submit → l1Approve → l2Approve → markPosted (POSTED); source invoice 保持 L2_APPROVED")
    void creditAdjustment_fullFlow() {
        // 1. 通过 BillingInvoiceApprovalIT 同模式构造 L2_APPROVED 源单据 (CNY)
        Long sourceInvoiceId = createAndApproveL2Invoice("CNY");

        // 2. create: DRAFT (currency 继承源单据 = CNY)
        BillingAdjustment adj = adjustmentService.create(tenantId, sourceInvoiceId,
            FinanceConstants.ADJ_TYPE_CREDIT,
            new BigDecimal("20.00"), "Credit note for refund of extra freight", 1001L);
        TenantContext.set(tenantId);
        assertThat(adj).isNotNull();
        assertThat(adj.getId()).isNotNull();
        assertThat(adj.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_DRAFT);
        assertThat(adj.getAdjustmentType()).isEqualTo("CREDIT");
        assertThat(adj.getCurrency()).isEqualTo("CNY");
        assertThat(adj.getSourceInvoiceId()).isEqualTo(sourceInvoiceId);
        assertThat(adj.getAdjustmentNo()).startsWith("ADJ-");
        // 数值字段已填充
        assertThat(adj.getGrossAmount()).isEqualByComparingTo(new BigDecimal("20.0000"));
        assertThat(adj.getTaxRate()).isNotNull();

        Long l1Operator = 1001L;
        Long l2Operator = 1002L;

        // 3. submit: DRAFT → PENDING_L1
        BillingAdjustment submitted = adjustmentService.submit(tenantId, adj.getId(), l1Operator);
        TenantContext.set(tenantId);
        assertThat(submitted.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_PENDING_L1);
        assertThat(submitted.getErrorMsg()).contains("submitted at");

        // 4. l1Approve: PENDING_L1 → PENDING_L2 (L1 终态为 PENDING_L2, 与 InvoiceService 对齐)
        BillingAdjustment l1Done = adjustmentService.l1Approve(tenantId, adj.getId(), l1Operator, "L1 ok");
        TenantContext.set(tenantId);
        assertThat(l1Done.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_PENDING_L2);
        assertThat(l1Done.getL1ApproverId()).isEqualTo(l1Operator);
        assertThat(l1Done.getL1ApprovedAt()).isNotNull();
        assertThat(l1Done.getL1Comment()).isEqualTo("L1 ok");
        // L2 字段仍未填充
        assertThat(l1Done.getL2ApproverId()).isNull();
        assertThat(l1Done.getL2ApprovedAt()).isNull();

        // 5. l2Approve: PENDING_L2 → L2_APPROVED (用不同操作员避免 L1_L2_SAME_OPERATOR)
        BillingAdjustment l2Done = adjustmentService.l2Approve(tenantId, adj.getId(), l2Operator, "L2 ok");
        TenantContext.set(tenantId);
        assertThat(l2Done.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_L2_APPROVED);
        assertThat(l2Done.getL2ApproverId()).isEqualTo(l2Operator);
        assertThat(l2Done.getL2ApprovedAt()).isNotNull();
        assertThat(l2Done.getL2Comment()).isEqualTo("L2 ok");
        // L1 字段保留
        assertThat(l2Done.getL1ApproverId()).isEqualTo(l1Operator);
        assertThat(l2Done.getL1Comment()).isEqualTo("L1 ok");

        // 6. markPosted: L2_APPROVED → POSTED (财务经理手动确认过账)
        BillingAdjustment posted = adjustmentService.markPosted(tenantId, adj.getId(), l2Operator);
        TenantContext.set(tenantId);
        assertThat(posted.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_POSTED);
        assertThat(posted.getErrorMsg()).contains("posted at");

        // 7. 状态机回退: POSTED → submit 应抛 INVALID_STATE_TRANSITION
        TenantContext.set(tenantId);
        assertThatThrownBy(() -> adjustmentService.submit(tenantId, adj.getId(), l1Operator))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVALID_STATE_TRANSITION);

        // 8. 回读 DB 验证: POSTED 字段全落库 + source invoice 状态保持 L2_APPROVED
        TenantContext.set(tenantId);
        List<BillingAdjustment> rows = billingAdjustmentMapper.selectList(
            new LambdaQueryWrapper<BillingAdjustment>()
                .eq(BillingAdjustment::getTenantId, tenantId)
                .eq(BillingAdjustment::getId, adj.getId()));
        assertThat(rows).hasSize(1);
        BillingAdjustment dbAdj = rows.get(0);
        assertThat(dbAdj.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_POSTED);
        assertThat(dbAdj.getL1ApproverId()).isEqualTo(l1Operator);
        assertThat(dbAdj.getL2ApproverId()).isEqualTo(l2Operator);
        assertThat(dbAdj.getCurrency()).isEqualTo("CNY");
        assertThat(dbAdj.getAdjustmentType()).isEqualTo("CREDIT");
        assertThat(dbAdj.getSourceInvoiceId()).isEqualTo(sourceInvoiceId);

        TenantContext.set(tenantId);
        BillingInvoice sourceAfter = billingInvoiceMapper.selectById(sourceInvoiceId);
        assertThat(sourceAfter).isNotNull();
        assertThat(sourceAfter.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_L2_APPROVED);
    }

    @Test
    @DisplayName("AdjustmentService.create: 源单据币种非 CNY/USD (EUR) → ADJUSTMENT_CURRENCY_MISMATCH")
    void creditAdjustment_crossCurrency_throws() {
        // 直接构造 EUR 源单据 (L2_APPROVED 状态) — 4.3.2 仅支持 CNY/USD, 其它币种触发
        // AdjustmentService.create 中的 ADJUSTMENT_CURRENCY_MISMATCH 校验.
        BillingInvoice inv = new BillingInvoice();
        inv.setTenantId(tenantId);
        inv.setInvoiceNo("INV-ADJ-EUR-" + System.nanoTime());
        inv.setDirection(FinanceConstants.DIR_RECEIVABLE);
        inv.setCounterpartyId(777L);
        inv.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        inv.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        inv.setGrossAmount(new BigDecimal("100.0000"));
        inv.setTaxAmount(BigDecimal.ZERO);
        inv.setNetAmount(new BigDecimal("100.0000"));
        inv.setCurrency("EUR");    // 4.3.2 未启用的币种
        inv.setFxRateAtCreation(BigDecimal.ONE);
        inv.setStatus(FinanceConstants.INV_STATUS_L2_APPROVED);
        inv.setRuleId(0L);
        inv.setRuleVersion(1);
        inv.setL1ApproverId(1L);
        inv.setL1ApprovedAt(java.time.LocalDateTime.now());
        inv.setL2ApproverId(2L);
        inv.setL2ApprovedAt(java.time.LocalDateTime.now());
        billingInvoiceMapper.insert(inv);
        Long sourceInvoiceId = inv.getId();
        TenantContext.set(tenantId);

        // 用普通 EUR 源单据 + CREDIT 调账应抛 ADJUSTMENT_CURRENCY_MISMATCH
        assertThatThrownBy(() -> adjustmentService.create(tenantId, sourceInvoiceId,
            FinanceConstants.ADJ_TYPE_CREDIT,
            new BigDecimal("20.00"), "Cross currency credit note attempt", 1001L))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ADJUSTMENT_CURRENCY_MISMATCH);

        // 验证未入库
        TenantContext.set(tenantId);
        List<BillingAdjustment> rows = billingAdjustmentMapper.selectList(
            new LambdaQueryWrapper<BillingAdjustment>()
                .eq(BillingAdjustment::getTenantId, tenantId));
        assertThat(rows).isEmpty();
    }

    // ---------- helpers ----------

    /**
     * 用 BillingInvoiceApprovalIT 完整流程构造 L2_APPROVED 源单据:
     * rule → accrual → finalize → invoice DRAFT → submit → l1Approve → l2Approve.
     * 不同 tenantId 用不同 effectiveFrom + priority 避开 t_billing_rule UNIQUE KEY.
     */
    private Long createAndApproveL2Invoice(String currency) {
        // 1. 建 ACTIVE rule
        View rule = createActiveRule(currency);

        // 2. 插 OPEN accrual + finalize → invoice DRAFT
        Long accrualId = insertOpenAccrual(rule.id(), currency);
        accrualService.finalize(tenantId, accrualId, 1L);
        TenantContext.set(tenantId);
        Long invoiceId = findInvoiceIdByAccrual(accrualId);

        // 3. submit: DRAFT → PENDING_L1
        invoiceService.submit(tenantId, invoiceId, 100L);
        TenantContext.set(tenantId);

        // 4. l1Approve: PENDING_L1 → PENDING_L2
        invoiceService.l1Approve(tenantId, invoiceId, 200L, "inv L1 ok");
        TenantContext.set(tenantId);

        // 5. l2Approve: PENDING_L2 → L2_APPROVED
        invoiceService.l2Approve(tenantId, invoiceId, 300L, "inv L2 ok");
        TenantContext.set(tenantId);

        return invoiceId;
    }

    /**
     * 用 BillingRuleService 建 ACTIVE rule.
     */
    private View createActiveRule(String currency) {
        LocalDate effFrom = LocalDate.now().minusDays(1 + (int) (tenantId % 30));
        int pri = (int) (tenantId % 1000);

        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_CUSTOMER,
            currency, pri,
            effFrom, null, "IT credit-adjustment rule",
            List.of(new CreateRuleItem(1, "BASE", "amount * 1.0", null, "gross_amount", null))
        );
        View created = billingRuleService.create(tenantId, 1L, req);
        return billingRuleService.activate(tenantId, created.id(), 1L);
    }

    /**
     * 用 mapper.insert 直接构造 OPEN accrual.
     */
    private Long insertOpenAccrual(Long ruleId, String currency) {
        BillingAccrual acc = new BillingAccrual();
        acc.setTenantId(tenantId);
        acc.setSourceEventId("it-credit-adj-" + System.nanoTime());
        acc.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        acc.setBusinessId(System.nanoTime());
        acc.setRuleId(ruleId);
        acc.setDirection(FinanceConstants.DIR_RECEIVABLE);
        acc.setCounterpartyId(777L);
        acc.setGrossAmount(new BigDecimal("100.00"));
        acc.setTaxAmount(BigDecimal.ZERO);
        acc.setNetAmount(new BigDecimal("100.00"));
        acc.setCurrency(currency);
        acc.setStatus(FinanceConstants.ACCRUAL_STATUS_OPEN);
        billingAccrualMapper.insert(acc);
        return acc.getId();
    }

    /**
     * 按 source_accrual_id 反查 invoice.
     */
    private Long findInvoiceIdByAccrual(Long accrualId) {
        List<BillingInvoice> rows = billingInvoiceMapper.selectList(
            new LambdaQueryWrapper<BillingInvoice>()
                .eq(BillingInvoice::getTenantId, tenantId)
                .eq(BillingInvoice::getSourceAccrualId, accrualId));
        assertThat(rows).hasSize(1);
        return rows.get(0).getId();
    }
}
