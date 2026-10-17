package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingRuleDtos.CreateRequest;
import com.lumen.finance.dto.BillingRuleDtos.CreateRuleItem;
import com.lumen.finance.dto.BillingRuleDtos.View;
import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.mapper.BillingAccrualMapper;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.service.AccrualService;
import com.lumen.finance.service.BillingRuleService;
import com.lumen.finance.service.InvoiceService;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
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
 * InvoiceService 端到端集成测试 (Iteration 4.3.1, PR3-T3.4).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. 复用 T3.1 (BillingRuleService)
 * 建 ACTIVE rule, T3.3 (AccrualService.finalize) 生成 invoice (DRAFT), 然后走
 * InvoiceService 1 级审批完整链路:
 * <ol>
 *   <li>submit: DRAFT → PENDING_L1 + submittedAt</li>
 *   <li>l1Approve: PENDING_L1 → L1_APPROVED + l1ApproverId / l1ApprovedAt / l1Comment</li>
 *   <li>l1Reject (另一条 invoice): PENDING_L1 → REJECTED + comment 必填验证</li>
 * </ol>
 *
 * <p>权限校验走 controller (T3.5), service 层只校验状态机 — 因此 IT 不验证 403
 * (需要 MockMvc + @WithMockUser, 留到 T3.5 controller IT).
 *
 * <p>每个测试用独占 tenantId (500_000+) 避开其他 IT 数据. 末尾清理 TenantContext.
 */
@DisplayName("BillingInvoiceApproval IT")
class BillingInvoiceApprovalIT extends IntegrationBase {

    @Autowired private InvoiceService invoiceService;
    @Autowired private AccrualService accrualService;
    @Autowired private BillingRuleService billingRuleService;
    @Autowired private BillingAccrualMapper billingAccrualMapper;
    @Autowired private BillingInvoiceMapper billingInvoiceMapper;

    /**
     * Counter-based unique tenantId generator. 起始 500_000L 避开
     * BillingAccrualFinalizeIT (400_000+) 与 BillingRuleActivationIT (200_000+) 占用段.
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(500_000L);
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
    @DisplayName("InvoiceService: DRAFT → submit → PENDING_L1 → l1Approve → L1_APPROVED (happy path)")
    void fullApprovalFlow_submitThenApprove_succeeds() {
        // 1. 建 ACTIVE rule (复用 T3.1)
        View rule = createActiveRule();

        // 2. 通过 accrual.generate → invoice DRAFT (复用 T3.3)
        Long accrualId = insertOpenAccrual(rule.id());
        accrualService.finalize(tenantId, accrualId, 1L);
        // finalize 不再返回 invoice 引用, IT 显式按 source_accrual_id 查
        TenantContext.set(tenantId);
        Long invoiceId = findInvoiceIdByAccrual(accrualId);
        BillingInvoice draft = billingInvoiceMapper.selectById(invoiceId);
        assertThat(draft).isNotNull();
        assertThat(draft.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_DRAFT);

        Long submitter = 100L;
        Long approver = 200L;

        // 3. submit: DRAFT → PENDING_L1
        BillingInvoice submitted = invoiceService.submit(tenantId, invoiceId, submitter);
        TenantContext.set(tenantId);  // 防止 TenantContext 在调用链中清掉
        assertThat(submitted.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L1);
        assertThat(submitted.getSubmittedAt()).isNotNull();

        // 4. 回读 DB 验证字段落库
        TenantContext.set(tenantId);
        BillingInvoice dbAfterSubmit = billingInvoiceMapper.selectById(invoiceId);
        assertThat(dbAfterSubmit).isNotNull();
        assertThat(dbAfterSubmit.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L1);
        assertThat(dbAfterSubmit.getSubmittedAt()).isNotNull();
        assertThat(dbAfterSubmit.getL1ApproverId()).isNull();   // submit 不写审批人
        assertThat(dbAfterSubmit.getL1ApprovedAt()).isNull();

        // 5. l1Approve: PENDING_L1 → L1_APPROVED
        BillingInvoice approved = invoiceService.l1Approve(tenantId, invoiceId, approver, "approved by IT");
        TenantContext.set(tenantId);
        assertThat(approved.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_L1_APPROVED);
        assertThat(approved.getL1ApproverId()).isEqualTo(approver);
        assertThat(approved.getL1ApprovedAt()).isNotNull();
        assertThat(approved.getL1Comment()).isEqualTo("approved by IT");
        // submittedAt 保留
        assertThat(approved.getSubmittedAt()).isNotNull();

        // 6. 回读 DB 验证 l1Approve 全部字段
        TenantContext.set(tenantId);
        BillingInvoice dbAfterApprove = billingInvoiceMapper.selectById(invoiceId);
        assertThat(dbAfterApprove).isNotNull();
        assertThat(dbAfterApprove.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_L1_APPROVED);
        assertThat(dbAfterApprove.getL1ApproverId()).isEqualTo(approver);
        assertThat(dbAfterApprove.getL1ApprovedAt()).isNotNull();
        assertThat(dbAfterApprove.getL1Comment()).isEqualTo("approved by IT");

        // 7. 状态机回退: L1_APPROVED → submit 应抛 INVALID_STATE_TRANSITION
        TenantContext.set(tenantId);
        assertThatThrownBy(() -> invoiceService.submit(tenantId, invoiceId, submitter))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("InvoiceService: DRAFT → submit → l1Reject → REJECTED (comment 必填)")
    void fullApprovalFlow_submitThenReject_succeeds() {
        // 1. 建 rule + draft invoice
        View rule = createActiveRule();
        Long accrualId = insertOpenAccrual(rule.id());
        accrualService.finalize(tenantId, accrualId, 1L);
        TenantContext.set(tenantId);
        Long invoiceId = findInvoiceIdByAccrual(accrualId);

        // 2. submit
        invoiceService.submit(tenantId, invoiceId, 100L);
        TenantContext.set(tenantId);

        // 3. l1Reject: comment 必填, blank / null 都应抛 INVOICE_REJECT_REASON_REQUIRED
        assertThatThrownBy(() -> invoiceService.l1Reject(tenantId, invoiceId, 200L, null))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED);
        assertThatThrownBy(() -> invoiceService.l1Reject(tenantId, invoiceId, 200L, "   "))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED);

        // 4. l1Reject 正常路径: PENDING_L1 → REJECTED
        BillingInvoice rejected = invoiceService.l1Reject(tenantId, invoiceId, 200L,
            "amount calculation off by 0.5");
        TenantContext.set(tenantId);
        assertThat(rejected.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_REJECTED);
        assertThat(rejected.getL1ApproverId()).isEqualTo(200L);
        assertThat(rejected.getL1ApprovedAt()).isNotNull();
        assertThat(rejected.getL1Comment()).isEqualTo("amount calculation off by 0.5");

        // 5. 回读 DB
        TenantContext.set(tenantId);
        BillingInvoice dbAfterReject = billingInvoiceMapper.selectById(invoiceId);
        assertThat(dbAfterReject).isNotNull();
        assertThat(dbAfterReject.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_REJECTED);
        assertThat(dbAfterReject.getL1ApproverId()).isEqualTo(200L);
        assertThat(dbAfterReject.getL1Comment()).isEqualTo("amount calculation off by 0.5");
    }

    @Test
    @DisplayName("InvoiceService.get: 跨租户隔离 → INVOICE_NOT_FOUND")
    void get_crossTenant_throwsInvoiceNotFound() {
        View rule = createActiveRule();
        Long accrualId = insertOpenAccrual(rule.id());
        accrualService.finalize(tenantId, accrualId, 1L);
        TenantContext.set(tenantId);
        Long invoiceId = findInvoiceIdByAccrual(accrualId);

        // 另一个 tenant 查询应抛 INVOICE_NOT_FOUND
        long otherTenant = tenantId + 999;
        assertThatThrownBy(() -> invoiceService.get(otherTenant, invoiceId))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVOICE_NOT_FOUND);
    }

    // ---------- helpers ----------

    /**
     * 用 BillingRuleService (T3.1) 建 ACTIVE rule.
     * 不同 tenantId 用不同 effectiveFrom + priority 避开 t_billing_rule UNIQUE KEY.
     */
    private View createActiveRule() {
        LocalDate effFrom = LocalDate.of(2026, 10, 1).plusDays((int) (tenantId % 365));
        int pri = (int) (tenantId % 1000);

        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_CUSTOMER,
            FinanceConstants.CURRENCY_CNY, pri,
            effFrom, null, "IT invoice-approval rule",
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
        acc.setSourceEventId("it-inv-" + System.nanoTime());
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
     * finalize 不再返回 invoice 引用 (4.3.1 PR3 Issue 3 fix), 按 source_accrual_id 反查.
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
