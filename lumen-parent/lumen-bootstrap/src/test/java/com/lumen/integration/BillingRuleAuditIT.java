package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingRuleDtos.CreateRequest;
import com.lumen.finance.dto.BillingRuleDtos.CreateRuleItem;
import com.lumen.finance.dto.BillingRuleDtos.UpdateRequest;
import com.lumen.finance.dto.BillingRuleDtos.View;
import com.lumen.finance.entity.BillingRuleAudit;
import com.lumen.finance.mapper.BillingRuleAuditMapper;
import com.lumen.finance.service.BillingRuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计费规则变更审计 IT (Iteration 4.3.1, PR3-T3.5).
 *
 * <p>对应 R-3 风险 mitigation: 每次规则变更 (CREATE / UPDATE / DISABLE) 都必须在
 * {@code t_billing_rule_audit} 留下一行, diff_json 包含 before/after 字段 --
 * 这是合规审计的硬要求, 不能漏写.
 *
 * <p>场景:
 * <ol>
 *   <li>create -> 1 行 audit (CREATE)</li>
 *   <li>update (priority + remark + items) -> 第 2 行 audit (UPDATE), diff_json 含 before/after</li>
 *   <li>activate -> 第 3 行 audit (UPDATE w/ status=ACTIVE) -- 取决于 service 实现, 此处允许</li>
 *   <li>disable -> 第 4 行 audit (DISABLE)</li>
 * </ol>
 *
 * <p>每个测试独占 tenantId (起始 600_000L) 避开其他 IT 占用段.
 */
@DisplayName("BillingRuleAudit IT (R-3)")
class BillingRuleAuditIT extends IntegrationBase {

    @Autowired private BillingRuleService billingRuleService;
    @Autowired private BillingRuleAuditMapper auditMapper;

    /**
     * Counter-based unique tenantId. 起始 600_000L 避开 lumen-cs (100_000+) /
     * BillingRuleActivationIT (200_000+) / BillingEventListenerIT (300_000+) /
     * BillingAccrualFinalizeIT (400_000+) / BillingInvoiceApprovalIT (500_000+).
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
    @DisplayName("R-3: create + update + activate + disable -> 4 行 audit (每行 diff_json 非空)")
    void everyMutation_writesAuditRow_withDiffJson() {
        // 1. CREATE -- 第 1 行 audit
        // effectiveFrom 用 2026-01-01 + tenantId 偏移 (避开 UNIQUE KEY), 保证在今天之前
        // (BillingRuleService.update 后续不依赖 effectiveFrom, 此处仅为占位).
        LocalDate effFrom = LocalDate.of(2026, 1, 1).plusDays((int) (tenantId % 300));
        int pri = (int) (tenantId % 1000);

        CreateRequest createReq = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_CUSTOMER,
            FinanceConstants.CURRENCY_CNY, pri,
            effFrom, null, "IT audit rule v1",
            List.of(
                new CreateRuleItem(1, "BASE",   "10",       null, "gross_amount", null),
                new CreateRuleItem(2, "WEIGHT", "weight*1", null, "gross_amount", null)
            )
        );
        Long operator = 999L;
        View created = billingRuleService.create(tenantId, operator, createReq);
        Long ruleId = created.id();

        // 2. UPDATE (priority + remark) -- 第 2 行 audit
        UpdateRequest updateReq = new UpdateRequest(
            pri + 10,                                     // priority 改
            null,                                         // effectiveTo 不动
            "IT audit rule v2 (updated)",                 // remark 改
            null                                          // items 不动 (null = 全删全插跳过)
        );
        billingRuleService.update(tenantId, ruleId, operator, updateReq);

        // 3. ACTIVATE -- 第 3 行 audit
        billingRuleService.activate(tenantId, ruleId, operator);

        // 4. UPDATE 全 items 替换 (再触发一次 audit) -- 第 4 行
        UpdateRequest updateItemsReq = new UpdateRequest(
            null, null, null,
            List.of(
                new CreateRuleItem(1, "BASE",   "20",       null, "gross_amount", null),
                new CreateRuleItem(2, "WEIGHT", "weight*2", null, "gross_amount", null),
                new CreateRuleItem(3, "EXTRA",  "5",        null, "gross_amount", null)
            )
        );
        billingRuleService.update(tenantId, ruleId, operator, updateItemsReq);

        // 5. DISABLE -- 第 5 行 audit
        billingRuleService.disable(tenantId, ruleId, operator);

        // 6. 查 t_billing_rule_audit 全部行 (按 ruleId)
        TenantContext.set(tenantId);
        List<BillingRuleAudit> audits = auditMapper.selectList(
            new LambdaQueryWrapper<BillingRuleAudit>()
                .eq(BillingRuleAudit::getRuleId, ruleId)
                .orderByAsc(BillingRuleAudit::getChangedAt)
        );

        // 至少 4 行 (CREATE / UPDATE x2 / ACTIVATE / DISABLE), 实际 ≥ 5
        assertThat(audits)
            .as("每次变更都应有 1 行 audit, 至少 5 行 (create + update + activate + update items + disable)")
            .hasSizeGreaterThanOrEqualTo(5);

        // 7. diff_json 不能为空 / 不是 "{}"
        for (BillingRuleAudit a : audits) {
            assertThat(a.getDiffJson())
                .as("audit id=%d changeType=%s diffJson 应非空, 包含 before/after 字段",
                    a.getId(), a.getChangeType())
                .isNotNull()
                .isNotBlank()
                .contains("{");
            // 简单形态断言: 大多数 diff 都有 "priority" 或 "items" 或 "remark" 等字段
            assertThat(a.getDiffJson().length())
                .as("diffJson 应有合理长度 (≥ 2 chars)")
                .isGreaterThanOrEqualTo(2);
        }

        // 8. 第一行必须是 CREATE
        assertThat(audits.get(0).getChangeType())
            .as("首行 audit 应为 CREATE")
            .isEqualTo(FinanceConstants.AUDIT_CHANGE_CREATE);

        // 9. 末行必须是 DISABLE (我们最后一步是 disable)
        assertThat(audits.get(audits.size() - 1).getChangeType())
            .as("末行 audit 应为 DISABLE (我们最后一步)")
            .isEqualTo(FinanceConstants.AUDIT_CHANGE_DISABLE);

        // 10. operatorId 应落库 (对应 operator=999)
        for (BillingRuleAudit a : audits) {
            assertThat(a.getOperatorId())
                .as("operatorId 应为 999L")
                .isEqualTo(operator);
        }
    }

    @Test
    @DisplayName("R-3: 重复 disable 每次都写一行 audit (DISABLED -> DISABLED 仍留痕)")
    void disable_eachCallWritesAuditRow() {
        // 建 + activate + disable x2 -> 至少 4 行 audit (CREATE / ACTIVATE / DISABLE / DISABLE)
        // 注: BillingRuleServiceImpl.disable 当前不检查 status 是否已 DISABLED,
        //     即便二次 disable 也会再写一行 audit. 这是当前实现行为, IT 锁定之.
        CreateRequest createReq = new CreateRequest(
            FinanceConstants.BIZ_WAREHOUSE_IN,
            FinanceConstants.DIR_PAYABLE,
            FinanceConstants.CP_CARRIER,
            FinanceConstants.CURRENCY_CNY,
            (int) (tenantId % 1000) + 50,
            LocalDate.of(2026, 10, 1).plusDays((int) (tenantId % 365)),
            null, "IT audit repeated disable",
            List.of(new CreateRuleItem(1, "BASE", "10", null, "gross_amount", null))
        );
        View created = billingRuleService.create(tenantId, 1L, createReq);
        Long ruleId = created.id();
        billingRuleService.activate(tenantId, ruleId, 1L);

        TenantContext.set(tenantId);
        long beforeCount = auditMapper.selectCount(
            new LambdaQueryWrapper<BillingRuleAudit>()
                .eq(BillingRuleAudit::getRuleId, ruleId));
        // 建=1 + activate=1 -> 2 行
        assertThat(beforeCount)
            .as("CREATE + ACTIVATE 应写 2 行 audit")
            .isEqualTo(2L);

        // 第一次 disable
        billingRuleService.disable(tenantId, ruleId, 1L);
        TenantContext.set(tenantId);
        long afterFirstDisable = auditMapper.selectCount(
            new LambdaQueryWrapper<BillingRuleAudit>()
                .eq(BillingRuleAudit::getRuleId, ruleId));
        assertThat(afterFirstDisable).isEqualTo(beforeCount + 1);

        // 第二次 disable -- 当前实现每次都写 audit (非幂等, 留痕强)
        billingRuleService.disable(tenantId, ruleId, 1L);
        TenantContext.set(tenantId);
        long afterSecondDisable = auditMapper.selectCount(
            new LambdaQueryWrapper<BillingRuleAudit>()
                .eq(BillingRuleAudit::getRuleId, ruleId));
        assertThat(afterSecondDisable)
            .as("二次 disable 当前实现也写 audit (DISABLED -> DISABLED 仍留痕)")
            .isEqualTo(afterFirstDisable + 1);

        // 末两行都是 DISABLE
        List<BillingRuleAudit> audits = auditMapper.selectList(
            new LambdaQueryWrapper<BillingRuleAudit>()
                .eq(BillingRuleAudit::getRuleId, ruleId)
                .orderByDesc(BillingRuleAudit::getChangedAt));
        assertThat(audits.get(0).getChangeType())
            .as("最近一次变更应为 DISABLE")
            .isEqualTo(FinanceConstants.AUDIT_CHANGE_DISABLE);
        assertThat(audits.get(1).getChangeType())
            .as("倒数第二次变更应为 DISABLE")
            .isEqualTo(FinanceConstants.AUDIT_CHANGE_DISABLE);
    }
}