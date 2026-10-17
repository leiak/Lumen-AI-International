package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingRuleDtos.CreateRequest;
import com.lumen.finance.dto.BillingRuleDtos.CreateRuleItem;
import com.lumen.finance.dto.BillingRuleDtos.View;
import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingAccrualMapper;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.service.AccrualService;
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
 * InvoiceService L2 终审端到端集成测试 (Iteration 4.3.2, PR3-T3.3).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. 复用 4.3.1 PR3 BillingInvoiceApprovalIT
 * 的 setup (建 ACTIVE rule → OPEN accrual → finalize → submit → PENDING_L1), 接下来走 L1 + L2:
 * <ol>
 *   <li>submit:  DRAFT → PENDING_L1</li>
 *   <li>l1Approve: PENDING_L1 → PENDING_L2 (4.3.2 PR3 起, L1 终态改为 PENDING_L2, 不是 L1_APPROVED)</li>
 *   <li>l2Approve: PENDING_L2 → L2_APPROVED, 写 l2ApproverId / l2ApprovedAt / l2Comment</li>
 *   <li>l2Reject:  PENDING_L2 → REJECTED, comment 必填校验</li>
 * </ol>
 *
 * <p>校验 4.3.2 PR3 加的硬约束: 同一操作员不能同时做 L1 + L2 — service 抛
 * {@code FinanceErrorCode.L1_L2_SAME_OPERATOR} (400_009).
 *
 * <p>每个测试用独占 tenantId (600_000+) 避开 4.3.1 IT (500_000+) 与
 * BillingAccrualFinalizeIT (400_000+) 占用段. 末尾清理 TenantContext.
 *
 * <p>权限校验走 controller (L2 endpoints 在 BillingInvoiceController 含
 * {@code @PreAuthorize("hasAuthority('finance:invoice:l2_approve')")}),
 * service 层只校验状态机 + 同操作员约束. 因此 IT 不验证 403 (需要 MockMvc + @WithMockUser).
 */
@DisplayName("BillingInvoiceL2Approval IT")
class BillingInvoiceL2ApprovalIT extends IntegrationBase {

    @Autowired private InvoiceService invoiceService;
    @Autowired private AccrualService accrualService;
    @Autowired private BillingRuleService billingRuleService;
    @Autowired private BillingAccrualMapper billingAccrualMapper;
    @Autowired private BillingInvoiceMapper billingInvoiceMapper;

    /**
     * Counter-based unique tenantId generator. 起始 600_000L 避开
     * BillingInvoiceApprovalIT (500_000+) / BillingAccrualFinalizeIT (400_000+) 等占用段.
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(600_000L);
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
    @DisplayName("InvoiceService: DRAFT → submit → l1Approve (PENDING_L2) → l2Approve → L2_APPROVED (happy path)")
    void fullL2ApprovalFlow_submitThenL1ApproveThenL2Approve_succeeds() {
        // 1. 建 ACTIVE rule (复用 4.3.1 PR3-T3.1)
        View rule = createActiveRule();

        // 2. OPEN accrual + finalize → invoice DRAFT (复用 T3.3)
        Long accrualId = insertOpenAccrual(rule.id());
        accrualService.finalize(tenantId, accrualId, 1L);
        TenantContext.set(tenantId);
        Long invoiceId = findInvoiceIdByAccrual(accrualId);
        BillingInvoice draft = billingInvoiceMapper.selectById(invoiceId);
        assertThat(draft).isNotNull();
        assertThat(draft.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_DRAFT);

        Long submitter = 100L;
        Long l1Operator = 200L;     // L1 操作员
        Long l2Operator = 300L;     // L2 操作员 (与 L1 不同人)

        // 3. submit: DRAFT → PENDING_L1
        BillingInvoice submitted = invoiceService.submit(tenantId, invoiceId, submitter);
        TenantContext.set(tenantId);
        assertThat(submitted.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L1);
        assertThat(submitted.getSubmittedAt()).isNotNull();

        // 4. l1Approve: PENDING_L1 → PENDING_L2 (4.3.2 PR3 起 L1 终态为 PENDING_L2)
        BillingInvoice l1Done = invoiceService.l1Approve(tenantId, invoiceId, l1Operator, "L1 ok");
        TenantContext.set(tenantId);
        assertThat(l1Done.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L2);
        assertThat(l1Done.getL1ApproverId()).isEqualTo(l1Operator);
        assertThat(l1Done.getL1ApprovedAt()).isNotNull();
        assertThat(l1Done.getL1Comment()).isEqualTo("L1 ok");
        // L2 字段仍未填充
        assertThat(l1Done.getL2ApproverId()).isNull();
        assertThat(l1Done.getL2ApprovedAt()).isNull();
        assertThat(l1Done.getL2Comment()).isNull();

        // 5. l2Approve: PENDING_L2 → L2_APPROVED
        BillingInvoice l2Done = invoiceService.l2Approve(tenantId, invoiceId, l2Operator, "L2 ok");
        TenantContext.set(tenantId);
        assertThat(l2Done.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_L2_APPROVED);
        assertThat(l2Done.getL2ApproverId()).isEqualTo(l2Operator);
        assertThat(l2Done.getL2ApprovedAt()).isNotNull();
        assertThat(l2Done.getL2Comment()).isEqualTo("L2 ok");
        // L1 字段保留
        assertThat(l2Done.getL1ApproverId()).isEqualTo(l1Operator);
        assertThat(l2Done.getL1ApprovedAt()).isNotNull();
        assertThat(l2Done.getL1Comment()).isEqualTo("L1 ok");
        assertThat(l2Done.getSubmittedAt()).isNotNull();

        // 6. 回读 DB 验证 l2Approve 全部字段落库
        TenantContext.set(tenantId);
        BillingInvoice dbAfterL2 = billingInvoiceMapper.selectById(invoiceId);
        assertThat(dbAfterL2).isNotNull();
        assertThat(dbAfterL2.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_L2_APPROVED);
        assertThat(dbAfterL2.getL1ApproverId()).isEqualTo(l1Operator);
        assertThat(dbAfterL2.getL1Comment()).isEqualTo("L1 ok");
        assertThat(dbAfterL2.getL2ApproverId()).isEqualTo(l2Operator);
        assertThat(dbAfterL2.getL2Comment()).isEqualTo("L2 ok");
        assertThat(dbAfterL2.getL2ApprovedAt()).isNotNull();

        // 7. 状态机回退: L2_APPROVED → l2Approve 应抛 INVALID_STATE_TRANSITION
        TenantContext.set(tenantId);
        assertThatThrownBy(() -> invoiceService.l2Approve(tenantId, invoiceId, l2Operator, "again"))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("InvoiceService.l2Approve: L1/L2 同操作员 → L1_L2_SAME_OPERATOR")
    void l2Approve_sameAsL1_throwsSameOperator() {
        View rule = createActiveRule();
        Long accrualId = insertOpenAccrual(rule.id());
        accrualService.finalize(tenantId, accrualId, 1L);
        TenantContext.set(tenantId);
        Long invoiceId = findInvoiceIdByAccrual(accrualId);

        // submit + l1Approve (PENDING_L1 → PENDING_L2)
        invoiceService.submit(tenantId, invoiceId, 100L);
        TenantContext.set(tenantId);
        Long sameOperator = 200L;
        invoiceService.l1Approve(tenantId, invoiceId, sameOperator, "L1 ok");
        TenantContext.set(tenantId);

        // l2Approve 用同一 operatorId 应抛 L1_L2_SAME_OPERATOR
        assertThatThrownBy(() -> invoiceService.l2Approve(tenantId, invoiceId, sameOperator, "trying L2"))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.L1_L2_SAME_OPERATOR);

        // 验证状态未被改变 (仍是 PENDING_L2)
        TenantContext.set(tenantId);
        BillingInvoice dbAfter = billingInvoiceMapper.selectById(invoiceId);
        assertThat(dbAfter).isNotNull();
        assertThat(dbAfter.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L2);
        assertThat(dbAfter.getL1ApproverId()).isEqualTo(sameOperator);
        assertThat(dbAfter.getL2ApproverId()).isNull();
        assertThat(dbAfter.getL2Comment()).isNull();

        // 用不同 operator 可成功 (作为控制对照)
        TenantContext.set(tenantId);
        BillingInvoice l2Done = invoiceService.l2Approve(tenantId, invoiceId, 999L, "different operator L2");
        TenantContext.set(tenantId);
        assertThat(l2Done.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_L2_APPROVED);
        assertThat(l2Done.getL2ApproverId()).isEqualTo(999L);
    }

    @Test
    @DisplayName("InvoiceService.l2Reject: blank comment → INVOICE_REJECT_REASON_REQUIRED")
    void l2Reject_blankComment_throwsRejectReasonRequired() {
        View rule = createActiveRule();
        Long accrualId = insertOpenAccrual(rule.id());
        accrualService.finalize(tenantId, accrualId, 1L);
        TenantContext.set(tenantId);
        Long invoiceId = findInvoiceIdByAccrual(accrualId);

        invoiceService.submit(tenantId, invoiceId, 100L);
        TenantContext.set(tenantId);
        invoiceService.l1Approve(tenantId, invoiceId, 200L, "L1 ok");
        TenantContext.set(tenantId);

        Long l2Operator = 300L;

        // 1. null comment → INVOICE_REJECT_REASON_REQUIRED
        assertThatThrownBy(() -> invoiceService.l2Reject(tenantId, invoiceId, l2Operator, null))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED);

        // 2. blank ("   ") comment → INVOICE_REJECT_REASON_REQUIRED
        assertThatThrownBy(() -> invoiceService.l2Reject(tenantId, invoiceId, l2Operator, "   "))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED);

        // 3. empty ("") comment → INVOICE_REJECT_REASON_REQUIRED
        assertThatThrownBy(() -> invoiceService.l2Reject(tenantId, invoiceId, l2Operator, ""))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED);

        // 4. 控制对照: 状态仍是 PENDING_L2, L2 字段全部 null
        TenantContext.set(tenantId);
        BillingInvoice dbAfter = billingInvoiceMapper.selectById(invoiceId);
        assertThat(dbAfter).isNotNull();
        assertThat(dbAfter.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L2);
        assertThat(dbAfter.getL2ApproverId()).isNull();
        assertThat(dbAfter.getL2ApprovedAt()).isNull();
        assertThat(dbAfter.getL2Comment()).isNull();

        // 5. 带真实 comment 可成功 l2Reject: PENDING_L2 → REJECTED
        TenantContext.set(tenantId);
        BillingInvoice rejected = invoiceService.l2Reject(tenantId, invoiceId, l2Operator,
            "amount/tax split incorrect");
        TenantContext.set(tenantId);
        assertThat(rejected.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_REJECTED);
        assertThat(rejected.getL2ApproverId()).isEqualTo(l2Operator);
        assertThat(rejected.getL2ApprovedAt()).isNotNull();
        assertThat(rejected.getL2Comment()).isEqualTo("amount/tax split incorrect");
        // L1 字段保留
        assertThat(rejected.getL1ApproverId()).isEqualTo(200L);
        assertThat(rejected.getL1Comment()).isEqualTo("L1 ok");

        // 6. 状态机回退: REJECTED → l2Reject 应抛 INVALID_STATE_TRANSITION
        TenantContext.set(tenantId);
        assertThatThrownBy(() -> invoiceService.l2Reject(tenantId, invoiceId, l2Operator, "again"))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVALID_STATE_TRANSITION);
    }

    // ---------- helpers (与 BillingInvoiceApprovalIT 保持一致, 4.3.2 PR3 起 tenantId 不同段) ----------

    /**
     * 用 BillingRuleService (4.3.1 PR3-T3.1) 建 ACTIVE rule.
     * 不同 tenantId 用不同 effectiveFrom + priority 避开 t_billing_rule UNIQUE KEY.
     */
    private View createActiveRule() {
        LocalDate effFrom = LocalDate.now().minusDays(1 + (int) (tenantId % 30));
        int pri = (int) (tenantId % 1000);

        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_CUSTOMER,
            FinanceConstants.CURRENCY_CNY, pri,
            effFrom, null, "IT L2-approval rule",
            List.of(new CreateRuleItem(1, "BASE", "amount * 1.0", null, "gross_amount", null))
        );
        View created = billingRuleService.create(tenantId, 1L, req);
        return billingRuleService.activate(tenantId, created.id(), 1L);
    }

    /**
     * 用 mapper.insert 直接构造 OPEN accrual (绕过 BillingEngineService.accrue).
     * sourceEventId 与 businessId 用 nanoTime 保证唯一, 避开
     * (tenant_id, source_event_id, business_type, deleted) UNIQUE KEY.
     */
    private Long insertOpenAccrual(Long ruleId) {
        BillingAccrual acc = new BillingAccrual();
        acc.setTenantId(tenantId);
        acc.setSourceEventId("it-l2-inv-" + System.nanoTime());
        acc.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        acc.setBusinessId(System.nanoTime());
        acc.setRuleId(ruleId);
        acc.setDirection(FinanceConstants.DIR_RECEIVABLE);
        acc.setCounterpartyId(777L);
        acc.setGrossAmount(new BigDecimal("150.00"));
        acc.setTaxAmount(BigDecimal.ZERO);
        acc.setNetAmount(new BigDecimal("150.00"));
        acc.setCurrency(FinanceConstants.CURRENCY_CNY);
        acc.setStatus(FinanceConstants.ACCRUAL_STATUS_OPEN);
        billingAccrualMapper.insert(acc);
        return acc.getId();
    }

    /**
     * finalize 不返回 invoice 引用 (4.3.1 PR3 Issue 3 fix), 按 source_accrual_id 反查.
     * UNIQUE KEY uk_source_accrual 保证唯一, 单条结果.
     */
    private Long findInvoiceIdByAccrual(Long accrualId) {
        List<BillingInvoice> rows = billingInvoiceMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BillingInvoice>()
                .eq(BillingInvoice::getTenantId, tenantId)
                .eq(BillingInvoice::getSourceAccrualId, accrualId));
        assertThat(rows).hasSize(1);
        return rows.get(0).getId();
    }
}
