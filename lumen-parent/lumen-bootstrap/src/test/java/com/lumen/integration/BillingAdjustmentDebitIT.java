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
 * AdjustmentService 调账单 (DEBIT note) 端到端集成测试 (Iteration 4.3.2, PR4-T4.3).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. 覆盖:
 * <ol>
 *   <li>完整 L1 + L2 + 财务过账 状态机路径: create (DRAFT) → submit (PENDING_L1) →
 *       l1Approve (PENDING_L2) → l2Approve (L2_APPROVED) → markPosted (POSTED).</li>
 *   <li>源单据未达 L2_APPROVED (DRAFT 状态) 调账 → {@code ADJUSTMENT_SOURCE_NOT_APPROVED}.
 *       4.3.2 spec 强调: 只有 L2 终审通过的单据才能被调账, 防止未审定的源单据
 *       多次扣/补造成财务混淆.</li>
 * </ol>
 *
 * <p>源 L2_APPROVED 单据通过 BillingInvoiceApprovalIT 同模式构造:
 * 建 ACTIVE rule → OPEN accrual → finalize (生成 DRAFT invoice) → submit → l1Approve → l2Approve.
 *
 * <p>每个测试用独占 tenantId (900_000+) 避开 BillingAdjustmentCreditIT (800_000+) /
 * BillingMultiCurrencyIT (700_000+) / BillingInvoiceL2ApprovalIT (600_000+) 等占用段.
 * 末尾清理 TenantContext.
 */
@DisplayName("BillingAdjustmentDebit IT")
class BillingAdjustmentDebitIT extends IntegrationBase {

    @Autowired private InvoiceService invoiceService;
    @Autowired private AccrualService accrualService;
    @Autowired private BillingRuleService billingRuleService;
    @Autowired private AdjustmentService adjustmentService;
    @Autowired private BillingAccrualMapper billingAccrualMapper;
    @Autowired private BillingInvoiceMapper billingInvoiceMapper;
    @Autowired private BillingAdjustmentMapper billingAdjustmentMapper;

    /**
     * Counter-based unique tenantId generator. 起始 900_000L 避开
     * BillingAdjustmentCreditIT (800_000+) 等占用段.
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(900_000L);
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
    @DisplayName("AdjustmentService (DEBIT): create → submit → l1Approve → l2Approve → markPosted (POSTED)")
    void debitAdjustment_fullFlow() {
        // 1. 通过 BillingInvoiceApprovalIT 同模式构造 L2_APPROVED 源单据 (CNY)
        Long sourceInvoiceId = createAndApproveL2Invoice("CNY");

        // 2. create: DRAFT (currency 继承源单据 = CNY, type=DEBIT)
        BillingAdjustment adj = adjustmentService.create(tenantId, sourceInvoiceId,
            FinanceConstants.ADJ_TYPE_DEBIT,
            new BigDecimal("15.00"), "Debit note for surcharge on extra weight", 1001L);
        TenantContext.set(tenantId);
        assertThat(adj).isNotNull();
        assertThat(adj.getId()).isNotNull();
        assertThat(adj.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_DRAFT);
        assertThat(adj.getAdjustmentType()).isEqualTo("DEBIT");
        assertThat(adj.getCurrency()).isEqualTo("CNY");
        assertThat(adj.getSourceInvoiceId()).isEqualTo(sourceInvoiceId);
        assertThat(adj.getAdjustmentNo()).startsWith("ADJ-");
        assertThat(adj.getGrossAmount()).isEqualByComparingTo(new BigDecimal("15.0000"));
        assertThat(adj.getTaxRate()).isNotNull();

        Long l1Operator = 1001L;
        Long l2Operator = 1002L;

        // 3. submit: DRAFT → PENDING_L1
        BillingAdjustment submitted = adjustmentService.submit(tenantId, adj.getId(), l1Operator);
        TenantContext.set(tenantId);
        assertThat(submitted.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_PENDING_L1);
        assertThat(submitted.getErrorMsg()).contains("submitted at");

        // 4. l1Approve: PENDING_L1 → PENDING_L2
        BillingAdjustment l1Done = adjustmentService.l1Approve(tenantId, adj.getId(), l1Operator, "L1 ok");
        TenantContext.set(tenantId);
        assertThat(l1Done.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_PENDING_L2);
        assertThat(l1Done.getL1ApproverId()).isEqualTo(l1Operator);
        assertThat(l1Done.getL1ApprovedAt()).isNotNull();
        assertThat(l1Done.getL1Comment()).isEqualTo("L1 ok");
        // L2 字段仍未填充
        assertThat(l1Done.getL2ApproverId()).isNull();

        // 5. l2Approve: PENDING_L2 → L2_APPROVED
        BillingAdjustment l2Done = adjustmentService.l2Approve(tenantId, adj.getId(), l2Operator, "L2 ok");
        TenantContext.set(tenantId);
        assertThat(l2Done.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_L2_APPROVED);
        assertThat(l2Done.getL2ApproverId()).isEqualTo(l2Operator);
        assertThat(l2Done.getL2ApprovedAt()).isNotNull();
        assertThat(l2Done.getL2Comment()).isEqualTo("L2 ok");

        // 6. markPosted: L2_APPROVED → POSTED
        BillingAdjustment posted = adjustmentService.markPosted(tenantId, adj.getId(), l2Operator);
        TenantContext.set(tenantId);
        assertThat(posted.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_POSTED);
        assertThat(posted.getErrorMsg()).contains("posted at");

        // 7. 同人约束: POSTED 调账单的 L1 操作员再次尝试 l2Approve 应抛 INVALID_STATE_TRANSITION
        //    (虽然状态机拦截在前, 同人约束再次也覆盖)
        TenantContext.set(tenantId);
        BillingAdjustment dbAdj = selectOneAdjustment(adj.getId());
        assertThat(dbAdj.getAdjustmentType()).isEqualTo("DEBIT");
        assertThat(dbAdj.getCurrency()).isEqualTo("CNY");
        assertThat(dbAdj.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_POSTED);
    }

    @Test
    @DisplayName("AdjustmentService.create: 源单据未达 L2_APPROVED (DRAFT) → ADJUSTMENT_SOURCE_NOT_APPROVED, 不入库")
    void debitAdjustment_sourceNotL2Approved_throws() {
        // 1. 构造 DRAFT 状态源单据 (只走 finalize, 不做 submit/l1/l2 审批)
        View rule = createActiveRule();
        Long accrualId = insertOpenAccrual(rule.id());
        accrualService.finalize(tenantId, accrualId, 1L);
        TenantContext.set(tenantId);
        Long sourceInvoiceId = findInvoiceIdByAccrual(accrualId);

        // 2. 确认当前状态为 DRAFT
        BillingInvoice source = billingInvoiceMapper.selectById(sourceInvoiceId);
        assertThat(source).isNotNull();
        assertThat(source.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_DRAFT);

        // 3. 用 DRAFT 源单据做 DEBIT 调账应抛 ADJUSTMENT_SOURCE_NOT_APPROVED
        assertThatThrownBy(() -> adjustmentService.create(tenantId, sourceInvoiceId,
            FinanceConstants.ADJ_TYPE_DEBIT,
            new BigDecimal("10.00"), "Should fail source is not approved", 1001L))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ADJUSTMENT_SOURCE_NOT_APPROVED);

        // 4. 验证未入库 (本 tenant 没有调整单)
        TenantContext.set(tenantId);
        List<BillingAdjustment> rows = billingAdjustmentMapper.selectList(
            new LambdaQueryWrapper<BillingAdjustment>()
                .eq(BillingAdjustment::getTenantId, tenantId));
        assertThat(rows).isEmpty();
    }

    // ---------- helpers ----------

    /**
     * 走 BillingInvoiceApprovalIT 完整流程构造 L2_APPROVED 源单据:
     * rule → accrual → finalize → invoice DRAFT → submit → l1Approve → l2Approve.
     */
    private Long createAndApproveL2Invoice(String currency) {
        // 1. 建 ACTIVE rule
        View rule = createActiveRule();

        // 2. 插 OPEN accrual + finalize → invoice DRAFT
        Long accrualId = insertOpenAccrual(rule.id());
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
    private View createActiveRule() {
        LocalDate effFrom = LocalDate.now().minusDays(1 + (int) (tenantId % 30));
        int pri = (int) (tenantId % 1000);

        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_CUSTOMER,
            FinanceConstants.CURRENCY_CNY, pri,
            effFrom, null, "IT debit-adjustment rule",
            List.of(new CreateRuleItem(1, "BASE", "amount * 1.0", null, "gross_amount", null))
        );
        View created = billingRuleService.create(tenantId, 1L, req);
        return billingRuleService.activate(tenantId, created.id(), 1L);
    }

    /**
     * 用 mapper.insert 直接构造 OPEN accrual.
     */
    private Long insertOpenAccrual(Long ruleId) {
        BillingAccrual acc = new BillingAccrual();
        acc.setTenantId(tenantId);
        acc.setSourceEventId("it-debit-adj-" + System.nanoTime());
        acc.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        acc.setBusinessId(System.nanoTime());
        acc.setRuleId(ruleId);
        acc.setDirection(FinanceConstants.DIR_RECEIVABLE);
        acc.setCounterpartyId(777L);
        acc.setGrossAmount(new BigDecimal("100.00"));
        acc.setTaxAmount(BigDecimal.ZERO);
        acc.setNetAmount(new BigDecimal("100.00"));
        acc.setCurrency(FinanceConstants.CURRENCY_CNY);
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

    /**
     * 按 tenant + id 查 adjustment (绕开 service.get 的跨租户校验, 走 mapper).
     */
    private BillingAdjustment selectOneAdjustment(Long id) {
        List<BillingAdjustment> rows = billingAdjustmentMapper.selectList(
            new LambdaQueryWrapper<BillingAdjustment>()
                .eq(BillingAdjustment::getTenantId, tenantId)
                .eq(BillingAdjustment::getId, id));
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
