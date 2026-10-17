package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingRuleDtos.CreateRequest;
import com.lumen.finance.dto.BillingRuleDtos.CreateRuleItem;
import com.lumen.finance.dto.BillingRuleDtos.View;
import com.lumen.finance.engine.CurrencyConverter;
import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.mapper.BillingAccrualMapper;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.service.AccrualService;
import com.lumen.finance.service.BillingRuleService;
import com.lumen.finance.service.ExchangeRateService;
import com.lumen.finance.service.InvoiceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InvoiceService 多币种 + tax 数学 端到端集成测试 (Iteration 4.3.2, PR3-T3.4).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. 覆盖:
 * <ol>
 *   <li>USD invoice: 通过 accrual.finalize → invoice DRAFT (currency=USD) → submit 走到 PENDING_L1,
 *       并校验 schema V30 的 fx_rate_at_creation 列存在 (目前 submit 不自动回填, 留作后续 PR 接线).</li>
 *   <li>{@link CurrencyConverter#splitGross} 对 USD 0% 与 CNY 13% 的数学正确性:
 *       tax = gross * rate / (1+rate), HALF_UP + 4 位小数.</li>
 * </ol>
 *
 * <p>每个测试用独占 tenantId (700_000+) 避开 BillingInvoiceL2ApprovalIT (600_000+) /
 * BillingInvoiceApprovalIT (500_000+) / BillingAccrualFinalizeIT (400_000+) 占用段.
 * 末尾清理 TenantContext.
 */
@DisplayName("BillingMultiCurrency IT")
class BillingMultiCurrencyIT extends IntegrationBase {

    @Autowired private InvoiceService invoiceService;
    @Autowired private AccrualService accrualService;
    @Autowired private BillingRuleService billingRuleService;
    @Autowired private ExchangeRateService exchangeRateService;
    @Autowired private BillingAccrualMapper billingAccrualMapper;
    @Autowired private BillingInvoiceMapper billingInvoiceMapper;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Counter-based unique tenantId generator. 起始 700_000L 避开
     * BillingInvoiceL2ApprovalIT (600_000+) / BillingInvoiceApprovalIT (500_000+) /
     * BillingAccrualFinalizeIT (400_000+) 等占用段.
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(700_000L);
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
    @DisplayName("USD invoice: accrue(USD) → submit preserves currency + V30 fx_rate_at_creation column is present")
    void usdInvoice_submit_acceptsAndPreservesCurrency() {
        // 1. 先插 USD→CNY 汇率 (V29 schema + ExchangeRateService.saveManual)
        LocalDate today = LocalDate.now();
        exchangeRateService.saveManual("USD", "CNY", new BigDecimal("7.1234"), today, 1001L);
        TenantContext.set(tenantId);
        BigDecimal rate = exchangeRateService.getRate("USD", "CNY", today);
        assertThat(rate).isEqualByComparingTo(new BigDecimal("7.1234"));

        // 2. 建 ACTIVE rule (与 BillingInvoiceL2ApprovalIT 同模式)
        View rule = createActiveRule();

        // 3. 插 OPEN accrual, currency=USD (覆盖 rule 默认 CNY). USD 单据在 4.3.2
        //    之前没有; 这里显式构造验证 accrue→finalize→invoice.currency 链路通了.
        Long accrualId = insertOpenAccrualUsd(rule.id());
        TenantContext.set(tenantId);

        // 4. finalize: OPEN → LOCKED → invoice DRAFT (currency=USD 从 accrual 透传)
        accrualService.finalize(tenantId, accrualId, 1L);
        TenantContext.set(tenantId);
        Long invoiceId = findInvoiceIdByAccrual(accrualId);
        BillingInvoice draft = billingInvoiceMapper.selectById(invoiceId);
        assertThat(draft).isNotNull();
        assertThat(draft.getCurrency()).isEqualTo("USD");
        assertThat(draft.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_DRAFT);

        // 5. submit: DRAFT → PENDING_L1
        BillingInvoice submitted = invoiceService.submit(tenantId, invoiceId, 100L);
        TenantContext.set(tenantId);
        assertThat(submitted.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L1);
        assertThat(submitted.getCurrency()).isEqualTo("USD");
        assertThat(submitted.getSubmittedAt()).isNotNull();

        // 6. DB 落库校验 (currency 走实体字段已足够; fx_rate_at_creation 列存在性
        //    走 JdbcTemplate — 实体层未暴露 fxRateAtCreation getter/setter).
        TenantContext.set(tenantId);
        BillingInvoice dbAfter = billingInvoiceMapper.selectById(invoiceId);
        assertThat(dbAfter).isNotNull();
        assertThat(dbAfter.getCurrency()).isEqualTo("USD");
        assertThat(dbAfter.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L1);

        BigDecimal fxRateAtCreation = jdbc.queryForObject(
            "SELECT fx_rate_at_creation FROM t_billing_invoice WHERE id = ?",
            BigDecimal.class, invoiceId);
        // 4.3.2 PR3 当前 submit() 不自动锁定 fx_rate_at_creation (entity 缺字段 +
        // service 未接线 ExchangeRateService.getRate). 这里确认 schema 列存在且为 NULL,
        // 留作 PR3+ / PR4 接线点. 期望后续 PR 在 submit() 末尾根据 invoice.currency
        // 调用 ExchangeRateService.getRate("USD", "CNY", LocalDate.now()) 回填.
        assertThat(fxRateAtCreation).isNull();

        // 8. exchangeRateService.list 能查到刚才 saveManual 的 USD→CNY 行 (链路通)
        List<com.lumen.finance.entity.BillingExchangeRate> rates =
            exchangeRateService.list("USD", "CNY", null, null, 1, 10);
        assertThat(rates).isNotEmpty();
        assertThat(rates.get(0).getRate()).isEqualByComparingTo(new BigDecimal("7.1234"));
        assertThat(rates.get(0).getSource()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("CurrencyConverter.splitGross: USD 0% (no tax) + CNY 13% (113→13/100)")
    void taxMath_usd0pct_cny13pct_correct() {
        // 1. USD 0%: tax = 0, net = gross (USD 出口/服务免税场景)
        BigDecimal[] usdResult = CurrencyConverter.splitGross(new BigDecimal("100.00"), BigDecimal.ZERO);
        assertThat(usdResult).hasSize(2);
        assertThat(usdResult[0].compareTo(BigDecimal.ZERO.setScale(CurrencyConverter.SCALE)))
            .as("USD 0% tax should be 0.0000").isEqualTo(0);
        assertThat(usdResult[1].compareTo(new BigDecimal("100.0000")))
            .as("USD 0% net should equal gross").isEqualTo(0);

        // 2. CNY 13%: 含税 113 → tax=13, net=100 (增值税典型计算)
        BigDecimal[] cnyResult = CurrencyConverter.splitGross(new BigDecimal("113.00"), new BigDecimal("0.13"));
        assertThat(cnyResult).hasSize(2);
        assertThat(cnyResult[0].compareTo(new BigDecimal("13.0000")))
            .as("CNY 13% tax of 113 should be 13.0000").isEqualTo(0);
        assertThat(cnyResult[1].compareTo(new BigDecimal("100.0000")))
            .as("CNY 13% net of 113 should be 100.0000").isEqualTo(0);

        // 3. CNY 6% 含税 106 → tax=6, net=100 (简化档位)
        BigDecimal[] cny6Result = CurrencyConverter.splitGross(new BigDecimal("106.00"), new BigDecimal("0.06"));
        assertThat(cny6Result).hasSize(2);
        assertThat(cny6Result[0].compareTo(new BigDecimal("6.0000")))
            .as("CNY 6% tax of 106 should be 6.0000").isEqualTo(0);
        assertThat(cny6Result[1].compareTo(new BigDecimal("100.0000")))
            .as("CNY 6% net of 106 should be 100.0000").isEqualTo(0);

        // 4. 跨币种 convert: USD 100 * 7.1234 = CNY 712.34 (4 位小数 + HALF_UP)
        BigDecimal converted = CurrencyConverter.convert(
            new BigDecimal("100.00"), "USD", "CNY", new BigDecimal("7.1234"));
        assertThat(converted).isNotNull();
        assertThat(converted.compareTo(new BigDecimal("712.3400"))).isEqualTo(0);

        // 5. 同币种 convert: 短路, 不乘 rate
        BigDecimal sameCcy = CurrencyConverter.convert(
            new BigDecimal("100.00"), "USD", "USD", new BigDecimal("7.1234"));
        assertThat(sameCcy.compareTo(new BigDecimal("100.0000"))).isEqualTo(0);
    }

    // ---------- helpers (与 BillingInvoiceL2ApprovalIT 保持一致, tenantId 700_000+ 段) ----------

    /**
     * 用 BillingRuleService 建 ACTIVE rule. 不同 tenantId 用不同 effectiveFrom + priority
     * 避开 t_billing_rule UNIQUE KEY.
     */
    private View createActiveRule() {
        LocalDate effFrom = LocalDate.now().minusDays(1 + (int) (tenantId % 30));
        int pri = (int) (tenantId % 1000);

        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_CUSTOMER,
            FinanceConstants.CURRENCY_CNY, pri,
            effFrom, null, "IT multi-currency rule",
            List.of(new CreateRuleItem(1, "BASE", "amount * 1.0", null, "gross_amount", null))
        );
        View created = billingRuleService.create(tenantId, 1L, req);
        return billingRuleService.activate(tenantId, created.id(), 1L);
    }

    /**
     * 用 mapper.insert 直接构造 OPEN accrual, currency=USD (覆盖 rule 默认 CNY).
     * sourceEventId 与 businessId 用 nanoTime 保证唯一, 避开
     * (tenant_id, source_event_id, business_type, deleted) UNIQUE KEY.
     */
    private Long insertOpenAccrualUsd(Long ruleId) {
        BillingAccrual acc = new BillingAccrual();
        acc.setTenantId(tenantId);
        acc.setSourceEventId("it-multi-ccy-" + System.nanoTime());
        acc.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        acc.setBusinessId(System.nanoTime());
        acc.setRuleId(ruleId);
        acc.setDirection(FinanceConstants.DIR_RECEIVABLE);
        acc.setCounterpartyId(777L);
        acc.setGrossAmount(new BigDecimal("100.00"));
        acc.setTaxAmount(BigDecimal.ZERO);
        acc.setNetAmount(new BigDecimal("100.00"));
        acc.setCurrency("USD");    // 多币种: 覆盖 rule 默认 CNY
        acc.setStatus(FinanceConstants.ACCRUAL_STATUS_OPEN);
        billingAccrualMapper.insert(acc);
        return acc.getId();
    }

    /**
     * 按 source_accrual_id 反查 invoice. UNIQUE KEY uk_source_accrual 保证单条结果.
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