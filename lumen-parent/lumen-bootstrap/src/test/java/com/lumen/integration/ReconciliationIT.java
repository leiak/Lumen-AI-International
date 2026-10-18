package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.PaymentRecordDtos.CreateRequest;
import com.lumen.finance.dto.ReconciliationDtos.Result;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.entity.BillingPaymentRecord;
import com.lumen.finance.entity.BillingReconciliation;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.mapper.BillingReconciliationMapper;
import com.lumen.finance.service.PaymentRecordService;
import com.lumen.finance.service.ReconciliationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReconciliationService 端到端集成测试 (Iteration 4.4, PR2-T2.5).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. 覆盖:
 * <ol>
 *   <li>{@code runReconciliation_createsPendingAndResolvesViaService}:
 *       准备 1 张 L2_APPROVED invoice (CNY 1000, RECEIVABLE, counterparty=300)
 *       + 1 条 payment_record (CNY 800, 同期) → runReconciliation 走 tenant+period
 *       过滤 → 产出 1 条 variance row (expected=1000, actual=800, variance=+200,
 *       status=ADJUSTMENT_CREATED) → resolveReconciliation 走 4-arg 重载 →
 *       状态变为 RESOLVED, 备注写入.</li>
 * </ol>
 *
 * <p><b>tenant 隔离</b>: 起始 TENANT_SEQ=1_500_000L (与 MonthlyClosingIT 共享段,
 * 但每个测试用不同 {@code incrementAndGet} 拿到独立 id; 1_500_001 / 1_500_002 /
 * 1_500_003). 避开所有现有 IT 占用段 (cs/escalation 100_000+ ... PaymentRecordCsvImport
 * 1_000_000+).
 *
 * <p><b>period 隔离</b>: 用 "2026-12" (与 MonthlyClosingIT 的 2026-10/2026-11
 * 不同), 避开同 JVM 内其他 IT 类的 payment_record UNIQUE 冲突 (同 tenantId+period
 * +direction+counterpartyId+paymentDate 唯一).
 *
 * <p><b>算法契合</b>: {@link ReconciliationServiceImpl#runReconciliation} 用
 * tenantId 过滤 invoice + payment (PR2.4 fix 已合入), 故 snapshot/variance 不受
 * 前序 IT 类的 L2_APPROVED invoice / payment_record 污染; 测试可断言
 * <b>绝对值</b> (expected=1000 / actual=800 / variance=200), 不必用 delta.
 */
@DisplayName("Reconciliation IT")
class ReconciliationIT extends IntegrationBase {

    @Autowired private ReconciliationService reconciliationService;
    @Autowired private PaymentRecordService paymentRecordService;
    @Autowired private BillingInvoiceMapper invoiceMapper;
    @Autowired private BillingReconciliationMapper reconMapper;

    /**
     * Counter-based unique tenantId. 起始 1_600_000L 避开所有现有 IT 占用段 + 同包内
     * 的 {@link MonthlyClosingIT} (起始 1_500_000L).
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(1_600_000L);
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
    @DisplayName("ReconciliationService: 1 invoice (CNY 1000) + 1 payment (CNY 800) → variance=200 → resolve → RESOLVED")
    void runReconciliation_createsPendingAndResolvesViaService() {
        Long userId = 1L;  // IT 无 SecurityContext, 硬编码
        String period = "2026-12";
        Long counterpartyId = 300L;

        // 1. 直接插 1 张 L2_APPROVED invoice (CNY 1000, RECEIVABLE, counterparty=300)
        //    跳过完整 BillingRule → accrual → finalize → submit → l1 → l2 流程,
        //    与 BillingAdjustmentCreditIT.creditAdjustment_crossCurrency_throws 同一模式.
        TenantContext.set(tenantId);
        BillingInvoice inv = new BillingInvoice();
        inv.setTenantId(tenantId);
        inv.setInvoiceNo("INV-RECON-" + System.nanoTime());
        inv.setDirection(FinanceConstants.DIR_RECEIVABLE);
        inv.setCounterpartyId(counterpartyId);
        inv.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        inv.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        inv.setGrossAmount(new BigDecimal("1000.0000"));
        inv.setTaxAmount(BigDecimal.ZERO);
        inv.setNetAmount(new BigDecimal("1000.0000"));
        inv.setCurrency(FinanceConstants.CURRENCY_CNY);
        inv.setFxRateAtCreation(BigDecimal.ONE);
        inv.setStatus(FinanceConstants.INV_STATUS_L2_APPROVED);
        inv.setRuleId(0L);
        inv.setRuleVersion(1);
        inv.setL1ApproverId(1L);
        inv.setL1ApprovedAt(LocalDateTime.now());
        inv.setL2ApproverId(2L);
        inv.setL2ApprovedAt(LocalDateTime.now());
        invoiceMapper.insert(inv);
        Long sourceInvoiceId = inv.getId();
        assertThat(sourceInvoiceId).isNotNull();

        // 2. 通过 PaymentRecordService.create 录入 1 条 payment (MANUAL, CNY 800, 同期)
        TenantContext.set(tenantId);
        CreateRequest payReq = new CreateRequest();
        payReq.setPeriod(period);
        payReq.setDirection(FinanceConstants.DIR_RECEIVABLE);
        payReq.setCounterpartyId(counterpartyId);
        payReq.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        payReq.setCurrency(FinanceConstants.CURRENCY_CNY);
        payReq.setAmount(new BigDecimal("800.00"));
        payReq.setPaymentDate(LocalDate.of(2026, 12, 20));
        payReq.setSourceRef("BANK-RECON-001");
        BillingPaymentRecord payment = paymentRecordService.create(tenantId, userId, payReq);
        assertThat(payment.getId()).isNotNull();
        assertThat(payment.getSource()).isEqualTo(FinanceConstants.PAY_SOURCE_MANUAL);

        // 3. 跑对账 (2-arg form: 返回 rich Result, 含 variance 列表 + autoAdjustmentIds)
        TenantContext.set(tenantId);
        Result result = reconciliationService.runReconciliation(tenantId, period);

        // 4. 断言: 至少 1 个 variance (单 invoice+payment, 应收 1000 vs 实收 800, +200)
        assertThat(result.getVarianceCount())
            .as("应收 1000 vs 实收 800, 应收 > 实收 → 1 条 variance (+200)")
            .isGreaterThanOrEqualTo(1);
        assertThat(result.getAutoAdjustmentIds())
            .as("runReconciliation 应自动生成 1 条调账 (DEBIT 应收补收)")
            .hasSizeGreaterThanOrEqualTo(1);

        // 5. 从 DB 反查对应 (tenant+period+direction+counterparty+currency) 行
        TenantContext.set(tenantId);
        List<BillingReconciliation> rows = reconMapper.selectList(
            new LambdaQueryWrapper<BillingReconciliation>()
                .eq(BillingReconciliation::getTenantId, tenantId)
                .eq(BillingReconciliation::getPeriod, period)
                .eq(BillingReconciliation::getDirection, FinanceConstants.DIR_RECEIVABLE)
                .eq(BillingReconciliation::getCounterpartyId, counterpartyId)
                .eq(BillingReconciliation::getCurrency, FinanceConstants.CURRENCY_CNY));
        // 可能有其他 tenant 的 invoice 干扰全表聚合, 但本 tenant 的 reconciliation 行
        // 只在自己 tenant 下产生 — 至少 1 行 (我们的 RECEIVABLE + counterparty=300)
        BillingReconciliation target = rows.stream()
            .filter(r -> FinanceConstants.RECON_STATUS_ADJUSTMENT_CREATED
                .equals(r.getReconciliationStatus())
                && counterpartyId.equals(r.getCounterpartyId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "expected 1 ADJUSTMENT_CREATED row for counterparty=" + counterpartyId
                    + ", got " + rows.size() + " rows: " + rows));
        Long reconId = target.getId();

        // 6. 验证字段: expected / actual / variance / status / 调账锚点
        assertThat(target.getExpectedAmount())
            .as("expected_amount = invoice netAmount = 1000")
            .isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(target.getActualAmount())
            .as("actual_amount = payment amount = 800")
            .isEqualByComparingTo(new BigDecimal("800"));
        assertThat(target.getVarianceAmount())
            .as("variance_amount = expected - actual = 200")
            .isEqualByComparingTo(new BigDecimal("200"));
        assertThat(target.getReconciliationStatus())
            .as("刚 run 完应是 ADJUSTMENT_CREATED (不是 RESOLVED / WAIVED)")
            .isEqualTo(FinanceConstants.RECON_STATUS_ADJUSTMENT_CREATED);
        assertThat(target.getAdjustmentId())
            .as("auto adjustmentId 应已写入 (BillingAdjustmentService.create 走 DRAFT)")
            .isNotNull();

        // 7. resolveReconciliation: 4-arg form (tenantId, id, note, userId) → RESOLVED
        TenantContext.set(tenantId);
        String resolveNote = "已确认收款, 银行流水匹配 (银行回单 REF-2026-12-001)";
        BillingReconciliation resolved = reconciliationService.resolveReconciliation(
            tenantId, reconId, resolveNote, userId);
        assertThat(resolved.getReconciliationStatus())
            .as("resolveReconciliation 后状态应为 RESOLVED")
            .isEqualTo(FinanceConstants.RECON_STATUS_RESOLVED);
        assertThat(resolved.getRemark())
            .as("resolve note 应写入 remark 字段")
            .contains(resolveNote);

        // 8. 回读 DB 验证 resolve 落库
        TenantContext.set(tenantId);
        BillingReconciliation dbAfter = reconMapper.selectById(reconId);
        assertThat(dbAfter).isNotNull();
        assertThat(dbAfter.getReconciliationStatus())
            .as("回读 DB: 状态应是 RESOLVED")
            .isEqualTo(FinanceConstants.RECON_STATUS_RESOLVED);
        assertThat(dbAfter.getRemark())
            .as("回读 DB: 备注应含 resolve note")
            .contains(resolveNote);
        assertThat(dbAfter.getUpdatedAt())
            .as("回读 DB: updatedAt 应被 touched (INSERT_UPDATE fill)")
            .isNotNull();

        // 9. 幂等: 再次 resolveReconciliation 已 RESOLVED 行不应报错 (resolveReconciliation 幂等)
        TenantContext.set(tenantId);
        BillingReconciliation secondResolve = reconciliationService.resolveReconciliation(
            tenantId, reconId, "二次 resolve 应幂等, 不抛错", userId);
        assertThat(secondResolve.getReconciliationStatus())
            .as("二次 resolve 仍 RESOLVED, 不应被回退 (幂等)")
            .isEqualTo(FinanceConstants.RECON_STATUS_RESOLVED);
    }
}
