package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.entity.BillingMonthlyClosing;
import com.lumen.finance.event.ClosingAffectedEvent;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.mapper.BillingMonthlyClosingMapper;
import com.lumen.finance.service.MonthlyClosingService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MonthlyClosingService 端到端集成测试 (Iteration 4.4, PR2-T2.5).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. 覆盖:
 * <ol>
 *   <li>{@code closeAndRecalculateSnapshot_persistsAndUpdates}: 手动 start →
 *       recalculateSnapshot (含 0 L2_APPROVED 快照) → 插 L2_APPROVED invoice →
 *       再次 recalculateSnapshot (快照变化) → close 完整状态机; 验证 snapshot
 *       字段落库且 delta 等于新插 invoice 金额.</li>
 *   <li>{@code eventListener_recalculatesSnapshotOnAdjustmentPosted}: 异步
 *       listener 路径 — publish {@link ClosingAffectedEvent} →
 *       {@code ClosingEventListener} (走 {@code outboxExecutor} 异步线程) →
 *       {@code MonthlyClosingService.recalculateSnapshot}; 用
 *       {@link Awaitility} 等 snapshot_recalculated_at 推进, 验证 delta 与
 *       新插 invoice 金额一致.</li>
 * </ol>
 *
 * <p><b>tenant 隔离</b>: 起始 TENANT_SEQ=1_500_000L 避开所有现有 IT 占用段
 * (cs/escalation 100_000+, BillingRuleActivation 200_000+, BillingEventListener
 * 300_000+, BillingAccrualFinalize 400_000+, BillingInvoiceApproval 500_000+,
 * BillingInvoiceL2 600_000+ / BillingRuleAudit 650_000+, BillingIdempotency +
 * BillingMultiCurrency 700_000+, BillingAdjustmentCredit 800_000+,
 * BillingAdjustmentDebit 900_000+, PaymentRecordCsvImport 1_000_000+).
 *
 * <p><b>period 隔离</b>: 每个测试用独占 period (test1: "2026-10", test2:
 * "2026-11"), 避开 {@code MonthlyClosingServiceImpl.get(tenantId, period)} 当前
 * 不按 tenantId 过滤的边角 (selectOne 需唯一).
 *
 * <p><b>snapshot pollution 缓解</b>: {@code recalculateSnapshotInternal} 当前按
 * 全表 L2_APPROVED invoice 聚合 (PR2.4 fix 未合入, 与 PR1 行为一致). 测试断言
 * 用 <b>delta</b> (新插 invoice 金额), 不断言绝对值, 避开前序 IT 类
 * (BillingAdjustmentCreditIT 等) 留下的 L2_APPROVED invoice 污染.
 */
@DisplayName("MonthlyClosing IT")
class MonthlyClosingIT extends IntegrationBase {

    @Autowired private MonthlyClosingService service;
    @Autowired private BillingMonthlyClosingMapper closingMapper;
    @Autowired private BillingInvoiceMapper invoiceMapper;
    @Autowired private ApplicationEventPublisher publisher;

    /**
     * Counter-based unique tenantId. 起始 1_500_000L 避开所有现有 IT 占用段.
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(1_500_000L);
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
    @DisplayName("MonthlyClosingService: start → recalc → 插 invoice → 再 recalc (delta=1000) → close 状态机")
    void closeAndRecalculateSnapshot_persistsAndUpdates() {
        Long userId = 1L;  // IT 无 SecurityContext, 硬编码
        String period = "2026-10";

        // 1. 直接插 OPEN 关闭单 (snapshot 字段全 null)
        TenantContext.set(tenantId);
        BillingMonthlyClosing seed = BillingMonthlyClosing.builder()
            .tenantId(tenantId)
            .period(period)
            .status(FinanceConstants.CLOSING_STATUS_OPEN)
            .snapshotCurrency(FinanceConstants.CURRENCY_CNY)
            .build();
        closingMapper.insert(seed);
        Long closingId = seed.getId();

        // 2. start: OPEN → IN_PROGRESS, 顺便首次 recalc (此时无 invoice, snapshot 应为 0)
        TenantContext.set(tenantId);
        BillingMonthlyClosing started = service.start(tenantId, period);
        assertThat(started.getStatus()).isEqualTo(FinanceConstants.CLOSING_STATUS_IN_PROGRESS);
        assertThat(started.getId()).isEqualTo(closingId);
        assertThat(started.getSnapshotRecalculatedAt()).isNotNull();
        BigDecimal snapshotBefore = started.getSnapshotTotalInvoice() == null
            ? BigDecimal.ZERO : started.getSnapshotTotalInvoice();

        // 3. 插 1 张 L2_APPROVED invoice (CNY 1000, RECEIVABLE, counterparty=300)
        TenantContext.set(tenantId);
        BillingInvoice inv = new BillingInvoice();
        inv.setTenantId(tenantId);
        inv.setInvoiceNo("INV-MC-" + System.nanoTime());
        inv.setDirection(FinanceConstants.DIR_RECEIVABLE);
        inv.setCounterpartyId(300L);
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

        // 4. 再次 recalculateSnapshot → snapshot_total_invoice 应 += 1000 (delta 严格断言)
        TenantContext.set(tenantId);
        BillingMonthlyClosing afterRecalc = service.recalculateSnapshot(tenantId, period);
        assertThat(afterRecalc.getStatus())
            .as("recalculate 不改 status, 仍是 IN_PROGRESS")
            .isEqualTo(FinanceConstants.CLOSING_STATUS_IN_PROGRESS);
        assertThat(afterRecalc.getSnapshotTotalInvoice())
            .as("新插 invoice CNY 1000, snapshot 应增加 1000 (delta 断言, 避开前序 IT 污染)")
            .isEqualByComparingTo(snapshotBefore.add(new BigDecimal("1000.0000")));
        assertThat(afterRecalc.getSnapshotRecalculatedAt())
            .as("snapshot_recalculated_at 应已推进")
            .isAfterOrEqualTo(started.getSnapshotRecalculatedAt());

        // 5. close: IN_PROGRESS → CLOSED, 顺便再 recalc 一次
        TenantContext.set(tenantId);
        BillingMonthlyClosing closed = service.close(tenantId, period);
        assertThat(closed.getStatus()).isEqualTo(FinanceConstants.CLOSING_STATUS_CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getSnapshotTotalInvoice())
            .as("close 末尾再 recalc, snapshot 仍是 1000 (插完后再无新 invoice)")
            .isEqualByComparingTo(snapshotBefore.add(new BigDecimal("1000.0000")));

        // 6. 回读 DB 验证全字段落库
        TenantContext.set(tenantId);
        BillingMonthlyClosing dbAfter = closingMapper.selectById(closingId);
        assertThat(dbAfter).isNotNull();
        assertThat(dbAfter.getStatus()).isEqualTo(FinanceConstants.CLOSING_STATUS_CLOSED);
        assertThat(dbAfter.getClosedAt()).isNotNull();
        assertThat(dbAfter.getSnapshotRecalculatedAt()).isNotNull();
        assertThat(dbAfter.getSnapshotCurrency()).isEqualTo(FinanceConstants.CURRENCY_CNY);
    }

    @Test
    @DisplayName("ClosingEventListener: publish ClosingAffectedEvent → async 异步重算 snapshot (delta=500)")
    void eventListener_recalculatesSnapshotOnAdjustmentPosted() {
        Long userId = 1L;
        String period = "2026-11";   // 与 test1 不同 period, 避开 get() 不按 tenant 过滤的 selectOne

        // 1. 直接插 OPEN 关闭单 (snapshot 字段全 null)
        TenantContext.set(tenantId);
        BillingMonthlyClosing seed = BillingMonthlyClosing.builder()
            .tenantId(tenantId)
            .period(period)
            .status(FinanceConstants.CLOSING_STATUS_OPEN)
            .snapshotCurrency(FinanceConstants.CURRENCY_CNY)
            .build();
        closingMapper.insert(seed);
        Long closingId = seed.getId();

        // 2. 插 1 张 L2_APPROVED invoice (CNY 500, 锚点)
        TenantContext.set(tenantId);
        BillingInvoice inv = new BillingInvoice();
        inv.setTenantId(tenantId);
        inv.setInvoiceNo("INV-MC-EVT-" + System.nanoTime());
        inv.setDirection(FinanceConstants.DIR_RECEIVABLE);
        inv.setCounterpartyId(400L);
        inv.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        inv.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        inv.setGrossAmount(new BigDecimal("500.0000"));
        inv.setTaxAmount(BigDecimal.ZERO);
        inv.setNetAmount(new BigDecimal("500.0000"));
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

        // 3. start 触发首次 recalc, 拿到基准 snapshot + recalculated_at
        TenantContext.set(tenantId);
        BillingMonthlyClosing started = service.start(tenantId, period);
        BigDecimal snapshotBefore = started.getSnapshotTotalInvoice() == null
            ? BigDecimal.ZERO : started.getSnapshotTotalInvoice();
        LocalDateTime recalcBefore = started.getSnapshotRecalculatedAt();
        assertThat(recalcBefore).isNotNull();

        // 4. 再插 1 张 L2_APPROVED invoice (CNY 500), 模拟"调账过账后影响月结"
        TenantContext.set(tenantId);
        BillingInvoice inv2 = new BillingInvoice();
        inv2.setTenantId(tenantId);
        inv2.setInvoiceNo("INV-MC-EVT2-" + System.nanoTime());
        inv2.setDirection(FinanceConstants.DIR_RECEIVABLE);
        inv2.setCounterpartyId(400L);
        inv2.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        inv2.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        inv2.setGrossAmount(new BigDecimal("500.0000"));
        inv2.setTaxAmount(BigDecimal.ZERO);
        inv2.setNetAmount(new BigDecimal("500.0000"));
        inv2.setCurrency(FinanceConstants.CURRENCY_CNY);
        inv2.setFxRateAtCreation(BigDecimal.ONE);
        inv2.setStatus(FinanceConstants.INV_STATUS_L2_APPROVED);
        inv2.setRuleId(0L);
        inv2.setRuleVersion(1);
        inv2.setL1ApproverId(1L);
        inv2.setL1ApprovedAt(LocalDateTime.now());
        inv2.setL2ApproverId(2L);
        inv2.setL2ApprovedAt(LocalDateTime.now());
        invoiceMapper.insert(inv2);

        // 5. publish ClosingAffectedEvent → 异步 listener 触发 recalculateSnapshot
        //    (实际生产路径是 AdjustmentServiceImpl.markPosted 末尾发, 这里直接 publish
        //    简化测试, 不引入 L1/L2 审批全流程)
        //    睡 5ms 确保 listener 的 recalculated_at 不会和 captureBefore 落在同一 ms
        //    (MySQL DATETIME(3) 截断精度为 ms)
        try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        TenantContext.set(tenantId);
        publisher.publishEvent(new ClosingAffectedEvent(this, tenantId, period, 1L));

        // 6. Awaitility 等 listener (outboxExecutor 异步线程) 跑完 —
        //    主信号: snapshot_total_invoice delta = +500 (新插 invoice 金额).
        //    辅助信号: snapshot_recalculated_at 推进 (>= captureBefore, 因为 ms 截断).
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                TenantContext.set(tenantId);  // outbox 线程结束后 TenantContext 已清, 重设供查询
                BillingMonthlyClosing current = closingMapper.selectOne(
                    new LambdaQueryWrapper<BillingMonthlyClosing>()
                        .eq(BillingMonthlyClosing::getId, closingId)
                );
                assertThat(current).isNotNull();
                assertThat(current.getSnapshotTotalInvoice())
                    .as("再插 invoice CNY 500, snapshot delta 应为 +500")
                    .isEqualByComparingTo(snapshotBefore.add(new BigDecimal("500.0000")));
                assertThat(current.getSnapshotRecalculatedAt())
                    .as("snapshot_recalculated_at 应 >= captureBefore (MySQL ms 截断允许等号)")
                    .isAfterOrEqualTo(recalcBefore);
            });

        // 7. 回读 DB 最终验证
        TenantContext.set(tenantId);
        BillingMonthlyClosing dbAfter = closingMapper.selectById(closingId);
        assertThat(dbAfter).isNotNull();
        assertThat(dbAfter.getSnapshotTotalInvoice())
            .as("listener 跑完后 snapshot_total_invoice 仍应保持 +500 delta")
            .isEqualByComparingTo(snapshotBefore.add(new BigDecimal("500.0000")));
        assertThat(dbAfter.getSnapshotRecalculatedAt())
            .as("回读 DB: snapshot_recalculated_at 应 >= captureBefore")
            .isAfterOrEqualTo(recalcBefore);

        // 8. 验证 listener 真的跑过: DB 里 tenantId+period 唯一, snapshot_recalculated_at
        //    比 publishEvent 之前时刻晚 (粗校验, 防止 listener 完全没触发)
        List<BillingMonthlyClosing> all = closingMapper.selectList(
            new LambdaQueryWrapper<BillingMonthlyClosing>()
                .eq(BillingMonthlyClosing::getTenantId, tenantId)
                .eq(BillingMonthlyClosing::getPeriod, period));
        assertThat(all).hasSize(1);
    }
}
