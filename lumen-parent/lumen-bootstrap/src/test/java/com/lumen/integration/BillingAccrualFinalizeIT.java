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

/**
 * AccrualService.finalize 端到端集成测试 (Iteration 4.3.1, PR3-T3.3).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. 复用 BillingRuleService (T3.1)
 * 建 ACTIVE rule, 通过 mapper.insert 直接构造 OPEN accrual (绕过 BillingEngineService,
 * 因为 IT 关心的是 finalize/release 路径), 然后走 AccrualService.finalize 触发完整链路:
 * <ol>
 *   <li>状态机校验 OPEN → LOCKED</li>
 *   <li>accrual update: status=LOCKED + lockedAt</li>
 *   <li>构造 + 插入 invoice (DRAFT, invoice_no = AR/AP-{tenantId}-{epochMs}-{seq})</li>
 *   <li>状态机校验 LOCKED → CONVERTED</li>
 *   <li>accrual update: status=CONVERTED</li>
 * </ol>
 *
 * <p>每个测试用独占 tenantId (400_000+) 避开其他 IT 数据. 末尾清理 TenantContext.
 */
@DisplayName("BillingAccrualFinalize IT")
class BillingAccrualFinalizeIT extends IntegrationBase {

    @Autowired private AccrualService accrualService;
    @Autowired private BillingRuleService billingRuleService;
    @Autowired private BillingAccrualMapper billingAccrualMapper;
    @Autowired private BillingInvoiceMapper billingInvoiceMapper;

    /**
     * Counter-based unique tenantId generator. 起始 400_000L 避开 lumen-cs (100_000+),
     * BillingRuleActivationIT (200_000+) 与 BillingEventListenerIT (300_000+) 占用段.
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(400_000L);
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
    @DisplayName("AccrualService.finalize: OPEN → invoice DRAFT → CONVERTED, invoiceNo AR-/AP- 前缀")
    void finalize_openToConverted_createsInvoice() {
        // 1. 用 BillingRuleService 建 ACTIVE rule (复用 T3.1)
        View rule = createActiveRule(FinanceConstants.DIR_RECEIVABLE);

        // 2. mapper.insert 构造 OPEN accrual (不经过 BillingEngineService, IT 聚焦 finalize 链路)
        Long accrualId = insertOpenAccrual(rule.id(), FinanceConstants.DIR_RECEIVABLE,
            new BigDecimal("99.50"));

        // 3. 触发 finalize
        BillingInvoice invoice = accrualService.finalize(tenantId, accrualId, 1L);

        // 4. 断言: invoice 字段
        assertThat(invoice).isNotNull();
        assertThat(invoice.getId()).isNotNull();
        assertThat(invoice.getTenantId()).isEqualTo(tenantId);
        assertThat(invoice.getSourceAccrualId()).isEqualTo(accrualId);
        assertThat(invoice.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_DRAFT);
        assertThat(invoice.getDirection()).isEqualTo(FinanceConstants.DIR_RECEIVABLE);
        assertThat(invoice.getRuleId()).isEqualTo(rule.id());
        assertThat(invoice.getCurrency()).isEqualTo(FinanceConstants.CURRENCY_CNY);
        assertThat(invoice.getGrossAmount()).isEqualByComparingTo(new BigDecimal("99.50"));
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(invoice.getNetAmount()).isEqualByComparingTo(new BigDecimal("99.50"));
        // invoiceNo 格式: AR-{tenantId}-{epochMs}-{seq}, 4 段
        assertThat(invoice.getInvoiceNo()).startsWith("AR-");
        assertThat(invoice.getInvoiceNo().split("-")).hasSize(4);
        assertThat(invoice.getInvoiceNo()).contains("-" + tenantId + "-");

        // 5. 回读 DB: invoice 落库 (MyMetaObjectHandler 填充 createdAt/updatedAt)
        TenantContext.set(tenantId);
        BillingInvoice dbInvoice = billingInvoiceMapper.selectById(invoice.getId());
        assertThat(dbInvoice).isNotNull();
        assertThat(dbInvoice.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_DRAFT);
        assertThat(dbInvoice.getCreatedAt()).isNotNull();
        assertThat(dbInvoice.getUpdatedAt()).isNotNull();
        assertThat(dbInvoice.getVersion()).isNotNull();

        // 6. 回读 accrual: status=CONVERTED, lockedAt 非空
        BillingAccrual dbAccrual = billingAccrualMapper.selectById(accrualId);
        assertThat(dbAccrual).isNotNull();
        assertThat(dbAccrual.getStatus()).isEqualTo(FinanceConstants.ACCRUAL_STATUS_CONVERTED);
        assertThat(dbAccrual.getLockedAt()).isNotNull();
        // version 自增过一次 (LOCKED) + 一次 (CONVERTED) = 至少 2
        assertThat(dbAccrual.getVersion()).isGreaterThanOrEqualTo(2);

        // 7. 第二个 accrual finalize, invoiceNo 必须不同 (跨 accrual 唯一)
        Long accrualId2 = insertOpenAccrual(rule.id(), FinanceConstants.DIR_RECEIVABLE,
            new BigDecimal("200.00"));
        BillingInvoice invoice2 = accrualService.finalize(tenantId, accrualId2, 1L);
        assertThat(invoice2.getInvoiceNo()).isNotEqualTo(invoice.getInvoiceNo());

        // 8. PAYABLE 方向走 AP- 前缀
        View payableRule = createActiveRule(FinanceConstants.DIR_PAYABLE);
        Long payableAccrualId = insertOpenAccrual(payableRule.id(), FinanceConstants.DIR_PAYABLE,
            new BigDecimal("50.00"));
        BillingInvoice payableInvoice = accrualService.finalize(tenantId, payableAccrualId, 1L);
        assertThat(payableInvoice.getInvoiceNo()).startsWith("AP-");
        assertThat(payableInvoice.getDirection()).isEqualTo(FinanceConstants.DIR_PAYABLE);
    }

    // ---------- helpers ----------

    /**
     * 用 BillingRuleService (T3.1) 建 ACTIVE rule.
     * 不同 direction 用不同 effectiveFrom + priority 避开 t_billing_rule UNIQUE KEY.
     */
    private View createActiveRule(String direction) {
        String biz = FinanceConstants.BIZ_LAST_MILE;
        String cp = FinanceConstants.DIR_RECEIVABLE.equals(direction)
            ? FinanceConstants.CP_CUSTOMER : FinanceConstants.CP_CARRIER;
        LocalDate effFrom = LocalDate.of(2026, 10, 1).plusDays((int) (tenantId % 365));
        int pri = (int) (tenantId % 1000);

        CreateRequest req = new CreateRequest(
            biz, direction, cp,
            FinanceConstants.CURRENCY_CNY, pri,
            effFrom, null, "IT finalize rule",
            List.of(new CreateRuleItem(1, "BASE", "amount * 1.0", null, "gross_amount", null))
        );
        View created = billingRuleService.create(tenantId, 1L, req);
        return billingRuleService.activate(tenantId, created.id(), 1L);
    }

    /**
     * 用 mapper.insert 直接构造 OPEN accrual (绕过 BillingEngineService.accrue).
     * sourceEventId 与 businessId 用 nanoTime 保证唯一, 避开 (tenant_id, source_event_id,
     * business_type, deleted) UNIQUE KEY.
     */
    private Long insertOpenAccrual(Long ruleId, String direction, BigDecimal gross) {
        BillingAccrual acc = new BillingAccrual();
        acc.setTenantId(tenantId);
        acc.setSourceEventId("it-acc-" + System.nanoTime());
        acc.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        acc.setBusinessId(System.nanoTime());
        acc.setRuleId(ruleId);
        acc.setDirection(direction);
        acc.setCounterpartyId(999L);
        acc.setGrossAmount(gross);
        acc.setTaxAmount(BigDecimal.ZERO);
        acc.setNetAmount(gross);
        acc.setCurrency(FinanceConstants.CURRENCY_CNY);
        acc.setStatus(FinanceConstants.ACCRUAL_STATUS_OPEN);
        billingAccrualMapper.insert(acc);
        return acc.getId();
    }
}
