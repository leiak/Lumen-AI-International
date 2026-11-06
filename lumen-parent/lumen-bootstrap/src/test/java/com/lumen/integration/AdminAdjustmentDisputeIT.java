package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.entity.BillingReconciliation;
import com.lumen.finance.mapper.BillingAdjustmentMapper;
import com.lumen.finance.mapper.BillingReconciliationMapper;
import com.lumen.finance.service.AdjustmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5C-2.1 — admin 侧读得到商家的异议原话 (缺口 #13) 集成测试。
 *
 * <p><b>守的是什么。</b> 商家提异议后, 原话落在 {@code t_reconciliation} 的
 * {@code dispute_reason / dispute_amount / dispute_at} 三列 (V39); 但 admin 审批界面
 * 展示的是**调账单**, 而 {@code t_billing_adjustment} 没有这三列 —— 4.5C-2.1 把它做成
 * {@code AdjustmentService.get / list} 回填的**瞬态快照** (只读, 不落库)。
 *
 * <p><b>为什么要直接插表而不是走 service。</b> 商家的异议是门户侧的动作, 在 finance
 * 模块的 IT 里复现它要绕一整圈 (登录 → 提异议 → 闸门)。这里直接 INSERT
 * {@code t_reconciliation} 的 dispute 三列, 把测试聚焦在"快照回填对不对"这一件事上。
 *
 * <p><b>两个必须注意的表结构事实。</b>
 * <ul>
 *   <li>{@code t_reconciliation} 的唯一键是
 *       {@code (tenant_id, period, direction, counterparty_id, currency, deleted)} ——
 *       同一租户下同期间同商家同币种只能有一行。造"一张调账单被两条 recon 认领"的脏数据
 *       时**必须用不同 period**, 否则 INSERT 撞唯一键。</li>
 *   <li>{@code BillingReconciliation} 实体**没有** {@code deleted} 字段 (DDL 有),
 *       {@code BillingAdjustment} 连 {@code deleted} 列都没有 —— 插数据时不要 set
 *       {@code deleted}, 否则 {@code Unknown column 'deleted'}。</li>
 * </ul>
 *
 * <p><b>租户段 1_200_000+</b> —— 避开 {@link PaymentRecordTenantIT} 的 1_100_000+ 与
 * 其它既有 IT 段。
 */
@DisplayName("Admin 调账单异议快照 IT")
class AdminAdjustmentDisputeIT extends IntegrationBase {

    @Autowired private AdjustmentService adjustmentService;
    @Autowired private BillingAdjustmentMapper adjustmentMapper;
    @Autowired private BillingReconciliationMapper reconMapper;

    /**
     * Counter-based unique tenantId. 起始 1_200_000L 避开所有现有 IT 占用段。
     *
     * <p>每个测试独占一个租户 —— 调账单是<b>按租户</b>聚合的, 混租户会让
     * "本租户有几条调账单"这个断言失去意义。
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(1_200_000L);
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

    /**
     * 种一张调账单。
     *
     * <p>{@code created_at / updated_at} 由 {@code MyMetaObjectHandler} 自动填
     * (实体上标了 {@code @TableField(fill = INSERT)}), 手工 set 反而会与它打架。
     */
    private BillingAdjustment seedAdjustment(String no) {
        BillingAdjustment a = BillingAdjustment.builder()
                .tenantId(tenantId)
                .adjustmentNo(no)
                // source_invoice_id 故意指向一张**不存在**的发票: t_billing_adjustment 上只有
                // idx_adj_invoice 普通索引、没有 FK (V29), 本 IT 只关心异议快照回填, 不必为此
                // 引入发票依赖和额外清理。将来若给该列加 FK, 改这里 —— 先插一张 t_billing_invoice 真发票。
                .sourceInvoiceId(1L)
                .adjustmentType(FinanceConstants.ADJ_TYPE_CREDIT)
                .amount(new BigDecimal("100.0000"))
                .currency(FinanceConstants.CURRENCY_CNY)
                .fxRateAtCreation(new BigDecimal("1.00000000"))
                .taxRate(new BigDecimal("0.1300"))
                .grossAmount(new BigDecimal("113.0000"))
                .taxAmount(new BigDecimal("13.0000"))
                .netAmount(new BigDecimal("100.0000"))
                .reason("对账差异自动生成: 期间 2026-07 差异 -10.00 CNY")
                .status(FinanceConstants.ADJ_STATUS_PENDING_L1)
                .build();
        adjustmentMapper.insert(a);
        return a;
    }

    /**
     * 种一条认领了 {@code adj} 的对账行, 并带上商家的异议三件套。
     */
    private BillingReconciliation seedRecon(BillingAdjustment adj, String period, String reason) {
        BillingReconciliation r = BillingReconciliation.builder()
                .tenantId(tenantId)
                .period(period)
                .direction(FinanceConstants.DIR_RECEIVABLE)
                .counterpartyId(400L)
                .counterpartyType(FinanceConstants.CP_CUSTOMER)
                .currency(FinanceConstants.CURRENCY_CNY)
                .expectedAmount(new BigDecimal("100.0000"))
                .actualAmount(new BigDecimal("90.0000"))
                .varianceAmount(new BigDecimal("-10.0000"))
                .adjustmentId(adj.getId())
                .reconciliationStatus(FinanceConstants.RECON_STATUS_ADJUSTMENT_CREATED)
                .disputeAmount(new BigDecimal("-12.0000"))
                .disputeReason(reason)
                .disputeAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .build();
        reconMapper.insert(r);
        return r;
    }

    @Test
    @DisplayName("[N13-2] admin 的 get 带回商家异议理由 / 金额 / 时间")
    void get_carriesDisputeSnapshot() {
        BillingAdjustment adj = seedAdjustment("ADJ-" + tenantId + "-001");
        seedRecon(adj, "2026-07", "9 月 3 日那票没送到");

        BillingAdjustment got = adjustmentService.get(tenantId, adj.getId());

        assertThat(got.getDisputeReason()).isEqualTo("9 月 3 日那票没送到");
        assertThat(got.getDisputeAmount()).isEqualByComparingTo(new BigDecimal("-12.0000"));
        // 钉住具体值而不是只断言非空 —— 只断言 isNotNull 的话, 实现把 dispute_at
        // 错填成 created_at (行创建时间) 也能过, 字段就没被真正守住。
        assertThat(got.getDisputeAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 0));
    }

    @Test
    @DisplayName("[N13-7] list 带出异议; disputed=true 只回有异议的, false 只回没异议的")
    void list_filtersByDisputed() {
        BillingAdjustment disputed = seedAdjustment("ADJ-" + tenantId + "-002");
        seedRecon(disputed, "2026-07", "有异议的理由");
        BillingAdjustment clean = seedAdjustment("ADJ-" + tenantId + "-003");
        // 没有 recon 认领 clean → 无异议

        List<BillingAdjustment> all = adjustmentService.list(tenantId, null, null, 1, 50);
        assertThat(all).hasSize(2);
        assertThat(all).anyMatch(a -> "有异议的理由".equals(a.getDisputeReason()));
        assertThat(all).anyMatch(a -> a.getId().equals(clean.getId()) && a.getDisputeReason() == null);

        List<BillingAdjustment> onlyDisputed = adjustmentService.list(tenantId, null, Boolean.TRUE, 1, 50);
        assertThat(onlyDisputed).hasSize(1);
        assertThat(onlyDisputed.get(0).getId()).isEqualTo(disputed.getId());

        List<BillingAdjustment> onlyClean = adjustmentService.list(tenantId, null, Boolean.FALSE, 1, 50);
        assertThat(onlyClean).hasSize(1);
        assertThat(onlyClean.get(0).getId()).isEqualTo(clean.getId());
    }

    @Test
    @DisplayName("[N13-3] 同一 adjustment 被两条 recon 认领 → 取 dispute_at 最新的那条")
    void takesLatestDispute() {
        BillingAdjustment adj = seedAdjustment("ADJ-" + tenantId + "-004");
        seedRecon(adj, "2026-07", "较老的理由");
        // 直接再插一条更新的 recon (同 adjustment_id, 不同 period 以躲开唯一键)
        BillingReconciliation newer = BillingReconciliation.builder()
                .tenantId(tenantId)
                .period("2026-08")
                .direction(FinanceConstants.DIR_RECEIVABLE)
                .counterpartyId(400L)
                .counterpartyType(FinanceConstants.CP_CUSTOMER)
                .currency(FinanceConstants.CURRENCY_CNY)
                .expectedAmount(new BigDecimal("100.0000"))
                .actualAmount(new BigDecimal("90.0000"))
                .varianceAmount(new BigDecimal("-10.0000"))
                .adjustmentId(adj.getId())
                .reconciliationStatus(FinanceConstants.RECON_STATUS_ADJUSTMENT_CREATED)
                .disputeAmount(new BigDecimal("-1.0000"))
                .disputeReason("较新的理由")
                .disputeAt(LocalDateTime.of(2026, 9, 2, 10, 0))
                .build();
        reconMapper.insert(newer);

        BillingAdjustment got = adjustmentService.get(tenantId, adj.getId());

        assertThat(got.getDisputeReason()).isEqualTo("较新的理由");
        // 三件套必须来自**同一条** (较新的那条 recon) —— 只断言 reason 的话,
        // 实现拿了较新那条的 reason 却配上较老那条的 amount / at 也能过。
        assertThat(got.getDisputeAmount()).isEqualByComparingTo(new BigDecimal("-1.0000"));
        assertThat(got.getDisputeAt()).isEqualTo(LocalDateTime.of(2026, 9, 2, 10, 0));
    }
}
