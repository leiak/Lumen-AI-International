package com.lumen.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 4.5C-2 — 商家门户账单 IT 的数据 fixture.
 *
 * <p>继承 {@link PortalOrderFixtureBase} 拿到 alice→customer42 / carol→customer99
 * 两套 portal 身份, 本类只负责往 billing 五张表里种数据.
 *
 * <p><b>为什么手写 SQL 而不是调 finance 的 service。</b> 服务会连带跑应计、开票、
 * 过账、状态机一整套, IT 就从"验证商家能看到什么"变成"验证整个计费引擎" ——
 * 跑得慢、失败时定位困难, 而且月结快照会被别处的定时任务污染。直接 INSERT 只写
 * 商家视图关心的那几列, 无关列全部塞 0/占位值。
 *
 * <p><b>id 段</b>: 970_001 起, 与 {@link PortalOrderFixtureBase} 的 960_001 段
 * 完全错开, 两组 IT 各自 {@code @BeforeEach} 重建、互不干扰。
 *
 * <p><b>调账单按 {@code ADJ-45C2-} 前缀清理</b> 而不是按 counterparty ——
 * {@code t_billing_adjustment} 既没有 {@code counterparty_id} 也没有 {@code period},
 * 只能靠单号前缀认领。它有 {@code uk_adj_no (tenant_id, adjustment_no)} 唯一键,
 * 所以 {@code @BeforeEach} 也做一次同样的前置删除: 上一次跑崩了留下的行不会让
 * 这次的 INSERT 撞唯一键。
 *
 * <p><b>闭期方向</b> (spec §7.2 step 2): 商家<b>只在该期已 CLOSED 时</b>才能提异议 ——
 * 期次还在 OPEN / IN_PROGRESS 时数字还会变, 此时记下来的主张没有意义。
 * 本 fixture 的四个场景分别对应四道闸门, 每个场景占一个独立期间
 * ({@code uk_recon_tenant_period_cpty} 的列是
 * (tenant_id, period, direction, counterparty_id, currency, deleted),
 * 同商家同期间同币种只能有一条 {@code deleted=0} 的行)。
 */
abstract class PortalMerchantBillingFixture extends PortalOrderFixtureBase {

    private static final AtomicLong BILLING_ID = new AtomicLong(970_001L);

    /** 调账单号前缀 —— 本 fixture 的专属认领标记, 清理靠它。 */
    protected static final String ADJ_NO_PREFIX = "ADJ-45C2-";

    /** 已闭期 ({@code t_monthly_closing.status = CLOSED}) —— <b>可提异议</b>。 */
    protected static final String PERIOD_CLOSED = "2026-07";
    /** 未闭期 (月结 OPEN) —— <b>不可提异议</b> (数字还会变)。 */
    protected static final String PERIOD_OPEN = "2026-08";
    /**
     * "无关联调账单"那条专用的独立期间 (已闭期)。
     *
     * <p>必须与 {@link #PERIOD_CLOSED} 分开: {@code uk_recon_tenant_period_cpty} 的列是
     * (tenant_id, period, direction, counterparty_id, currency, deleted), 同商家同期间
     * 只能有一条 {@code deleted=0} 的行。共用 2026-07 的话, "无调账单"和"主路径"
     * 两条 recon 会直接撞唯一键。
     *
     * <p>它必须是<b>已闭期</b>的: 闭期闸门排在"有没有关联调账单"之前, 期次没闭的话
     * 请求会先被闭期闸门挡掉, 根本走不到调账单那一支, 那条分支就白测了。
     */
    protected static final String PERIOD_NOA = "2026-06";
    /**
     * "recon 状态非 ADJUSTMENT_CREATED (WAIVED)"那条专用的独立期间 (已闭期)。
     *
     * <p>同样必须是已闭期的 —— 闭期闸门在前, 期次没闭就测不到状态闸门。
     */
    protected static final String PERIOD_WAIVED = "2026-05";

    protected long adjDraftId;
    /** 已闭期 + 挂了 DRAFT 调账单 —— 异议主路径 (能提成功的那条)。 */
    protected long reconClosedId;
    /** 未闭期 + 挂了 DRAFT 调账单 —— 专测"期间未闭期", 其余条件都满足, 只差闭期。 */
    protected long reconOpenId;
    /** 已闭期 + 零差异 + 无关联调账单 —— 专测"没单可推"。 */
    protected long reconNoAdjId;
    /** 已闭期 + 状态 WAIVED —— 专测"状态非 ADJUSTMENT_CREATED" (财务已豁免, 不该被翻出来)。 */
    protected long reconWaivedId;

    @BeforeEach
    void seedBillingFixture() {
        long tenant = 1L;
        // 上一次跑崩的残留 — uk_adj_no 会让 INSERT 直接失败
        jdbc.update("DELETE FROM t_billing_adjustment WHERE tenant_id = 1 AND adjustment_no LIKE ?", ADJ_NO_PREFIX + "%");
        // recon / payment / invoice / monthly_closing 四张表同样都有唯一键
        // (uk_recon_tenant_period_cpty / uk_payment_tenant_period_cpty / uk_invoice_no /
        //  uk_closing_tenant_period), 上一次跑崩留下的行会让本轮的 INSERT 撞唯一键。
        // 撞唯一键的报错发生在 seed 阶段, 现场看起来像"fixture 写错了", 定位成本很高。
        // @AfterEach 会清干净, 但跑崩的那一轮不会执行到 —— 所以 @BeforeEach 也得先清一遍。
        for (long cid : new long[]{customer42Id, customer99Id}) {
            jdbc.update("DELETE FROM t_reconciliation WHERE tenant_id = 1 AND counterparty_id = ?", cid);
            jdbc.update("DELETE FROM t_payment_record WHERE tenant_id = 1 AND counterparty_id = ?", cid);
            jdbc.update("DELETE FROM t_billing_invoice WHERE tenant_id = 1 AND counterparty_id = ?", cid);
        }
        jdbc.update("DELETE FROM t_monthly_closing WHERE tenant_id = 1 AND period IN (?, ?, ?, ?)",
                PERIOD_CLOSED, PERIOD_NOA, PERIOD_OPEN, PERIOD_WAIVED);

        // ---- 发票: alice 2 张 (闭期/开放各一) + carol 1 张 ----
        seedInvoice(tenant, customer42Id, "INV-45C2-A1", 2026, 7, "113.00", "13.00", "100.00", "PUSHED");
        seedInvoice(tenant, customer42Id, "INV-45C2-A2", 2026, 8, "226.00", "26.00", "200.00", "PUSHED");
        seedInvoice(tenant, customer99Id, "INV-45C2-C1", 2026, 8, "339.00", "39.00", "300.00", "PUSHED");

        // ---- 调账单: 一张 DRAFT (挂到各条 recon 上, 供异议推进) ----
        // 只建**一张**: "我的调账单"归属链 (recon.adjustment_id + invoice.source_invoice_id)
        // 会把它算进 alice 的可见列表, 建两张会让可见性 IT 的条数断言漂移。
        // 需要"调整单状态不可用"这类分支时, 用同一条 recon 改状态去测。
        adjDraftId = BILLING_ID.incrementAndGet();
        jdbc.update(
            "INSERT INTO t_billing_adjustment (id, tenant_id, adjustment_no, source_invoice_id, adjustment_type, "
                + "amount, currency, fx_rate_at_creation, tax_rate, gross_amount, tax_amount, net_amount, "
                + "reason, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, 'DEBIT', ?, 'CNY', 1.00000000, 0.1300, ?, ?, ?, ?, 'DRAFT', NOW(3), NOW(3))",
            adjDraftId, tenant, ADJ_NO_PREFIX + "0001",
            new BigDecimal("11.30"), new BigDecimal("11.30"), new BigDecimal("1.30"), new BigDecimal("10.00"),
            "月结差异自动调账");

        // ---- 对账 ----
        // 主路径: 已闭期 + ADJUSTMENT_CREATED + 挂了调账单 → 能提异议
        reconClosedId = seedRecon(tenant, customer42Id, PERIOD_CLOSED,
                "100.00", "90.00", "-10.00", "ADJUSTMENT_CREATED", 0);
        jdbc.update("UPDATE t_reconciliation SET adjustment_id = ? WHERE id = ?", adjDraftId, reconClosedId);

        // 未闭期: 其余条件全对 (ADJUSTMENT_CREATED + 挂了调账单), 只差闭期 → 只会被闭期闸门拦下
        reconOpenId = seedRecon(tenant, customer42Id, PERIOD_OPEN,
                "100.00", "90.00", "-10.00", "ADJUSTMENT_CREATED", 0);
        jdbc.update("UPDATE t_reconciliation SET adjustment_id = ? WHERE id = ?", adjDraftId, reconOpenId);

        // 无关联调账单: 已闭期 + ADJUSTMENT_CREATED, 但 adjustment_id 为空
        // (调账单被删了, recon 还停在"已建调账单"的状态 —— 悬空的 adjustment_id)
        reconNoAdjId = seedRecon(tenant, customer42Id, PERIOD_NOA,
                "60.00", "50.00", "-10.00", "ADJUSTMENT_CREATED", 0);

        // 状态闸门: 已闭期 + 挂了调账单, 但 recon 状态是 WAIVED (财务已豁免)
        reconWaivedId = seedRecon(tenant, customer42Id, PERIOD_WAIVED,
                "60.00", "50.00", "-10.00", "WAIVED", 0);
        jdbc.update("UPDATE t_reconciliation SET adjustment_id = ? WHERE id = ?", adjDraftId, reconWaivedId);

        // 软删那条必须**带着 deleted=1 直接 INSERT** —— uk_recon_tenant_period_cpty
        // 的列含 deleted, 所以"先按 deleted=0 插进去、再 UPDATE 成 1"这种写法在
        // INSERT 那一刻就与同期间同商家的活行撞唯一键了 (Duplicate entry ...-CNY-0)。
        seedRecon(tenant, customer42Id, PERIOD_OPEN, "50.00", "50.00", "0.00", "MATCHED", 1);

        seedRecon(tenant, customer99Id, PERIOD_OPEN, "300.00", "280.00", "-20.00", "ADJUSTMENT_CREATED", 0);

        // ---- 付款: alice 2 条 (含 1 条软删) + carol 1 条 ----
        // 付款侧的软删行**也**直接带 deleted=1 落库 —— 但理由与 recon 侧不同:
        // uk_payment_tenant_period_cpty 的列是 (..., payment_date, deleted),
        // 关键是含 payment_date 这一个维度 —— alice 那两条付款的日期不同 (08-28 /
        // 08-29), 所以"先 deleted=0 插进去再 UPDATE 成 1"在付款侧其实不会撞键。
        // 这里统一成直接带 deleted 落库, 是为了跟 recon 侧写法一致 (少一条分支,
        // 读 fixture 的人不必判断"这条要不要 UPDATE"), 终态数据完全等价。
        seedPayment(tenant, customer42Id, PERIOD_OPEN, "100.00", LocalDate.of(2026, 8, 28), 0);
        seedPayment(tenant, customer42Id, PERIOD_OPEN, "77.00", LocalDate.of(2026, 8, 29), 1);
        seedPayment(tenant, customer99Id, PERIOD_OPEN, "300.00", LocalDate.of(2026, 8, 30), 0);

        // ---- 月结: 2026-05 / 06 / 07 已 CLOSED, 2026-08 开放 ----
        // 前三个已闭期是为了让"闭期闸门"放行, 好让各自后面那道闸门 (无调账单 / 状态
        // 非 ADJUSTMENT_CREATED) 有机会被测到 —— 闸门是有顺序的。
        jdbc.update("INSERT INTO t_monthly_closing (tenant_id, period, status, created_at, updated_at, deleted) "
                + "VALUES (?, ?, 'CLOSED', NOW(3), NOW(3), 0)", tenant, PERIOD_CLOSED);
        jdbc.update("INSERT INTO t_monthly_closing (tenant_id, period, status, created_at, updated_at, deleted) "
                + "VALUES (?, ?, 'CLOSED', NOW(3), NOW(3), 0)", tenant, PERIOD_NOA);
        jdbc.update("INSERT INTO t_monthly_closing (tenant_id, period, status, created_at, updated_at, deleted) "
                + "VALUES (?, ?, 'CLOSED', NOW(3), NOW(3), 0)", tenant, PERIOD_WAIVED);
        jdbc.update("INSERT INTO t_monthly_closing (tenant_id, period, status, created_at, updated_at, deleted) "
                + "VALUES (?, ?, 'OPEN', NOW(3), NOW(3), 0)", tenant, PERIOD_OPEN);
    }

    @AfterEach
    void cleanBillingFixture() {
        jdbc.update("DELETE FROM t_billing_adjustment WHERE tenant_id = 1 AND adjustment_no LIKE ?", ADJ_NO_PREFIX + "%");
        // recon / payment / invoice 三张表都带 counterparty_id, 按商家扫最不容易漏
        for (long cid : new long[]{customer42Id, customer99Id}) {
            jdbc.update("DELETE FROM t_reconciliation WHERE tenant_id = 1 AND counterparty_id = ?", cid);
            jdbc.update("DELETE FROM t_payment_record WHERE tenant_id = 1 AND counterparty_id = ?", cid);
            jdbc.update("DELETE FROM t_billing_invoice WHERE tenant_id = 1 AND counterparty_id = ?", cid);
        }
        jdbc.update("DELETE FROM t_monthly_closing WHERE tenant_id = 1 AND period IN (?, ?, ?, ?)",
                PERIOD_CLOSED, PERIOD_NOA, PERIOD_OPEN, PERIOD_WAIVED);
    }

    private long nextBillingId() {
        return BILLING_ID.incrementAndGet();
    }

    private void seedInvoice(long tenantId, long counterpartyId, String no, int year, int month,
                             String gross, String tax, String net, String status) {
        jdbc.update(
            "INSERT INTO t_billing_invoice (id, invoice_no, tenant_id, direction, counterparty_id, counterparty_type, "
                + "business_type, period_year, period_month, gross_amount, tax_amount, net_amount, currency, status, "
                + "rule_id, rule_version, deleted, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'RECEIVABLE', ?, 'CUSTOMER', 'LAST_MILE', ?, ?, ?, ?, ?, 'CNY', ?, 1, 1, 0, 0, NOW(3), NOW(3))",
            nextBillingId(), no, tenantId, counterpartyId, year, month,
            new BigDecimal(gross), new BigDecimal(tax), new BigDecimal(net), status);
    }

    private long seedRecon(long tenantId, long counterpartyId, String period,
                           String expected, String actual, String variance, String status, int deleted) {
        long id = nextBillingId();
        jdbc.update(
            "INSERT INTO t_reconciliation (id, tenant_id, period, direction, counterparty_id, counterparty_type, "
                + "currency, expected_amount, actual_amount, variance_amount, reconciliation_status, "
                + "created_at, updated_at, deleted) "
                + "VALUES (?, ?, ?, 'RECEIVABLE', ?, 'CUSTOMER', 'CNY', ?, ?, ?, ?, NOW(3), NOW(3), ?)",
            id, tenantId, period, counterpartyId,
            new BigDecimal(expected), new BigDecimal(actual), new BigDecimal(variance), status, deleted);
        return id;
    }

    private void seedPayment(long tenantId, long counterpartyId, String period,
                             String amount, LocalDate paymentDate, int deleted) {
        long id = nextBillingId();
        jdbc.update(
            "INSERT INTO t_payment_record (id, tenant_id, period, direction, counterparty_id, counterparty_type, "
                + "currency, amount, source_ref, payment_date, source, created_at, created_by, deleted) "
                + "VALUES (?, ?, ?, 'RECEIVABLE', ?, 'CUSTOMER', 'CNY', ?, ?, ?, 'BANK_TRANSFER', NOW(3), 1, ?)",
            id, tenantId, period, counterpartyId,
            new BigDecimal(amount), "REF-" + id, paymentDate, deleted);
    }
}
