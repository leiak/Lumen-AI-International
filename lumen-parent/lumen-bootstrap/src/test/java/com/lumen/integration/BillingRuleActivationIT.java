package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingRuleDtos.CreateRequest;
import com.lumen.finance.dto.BillingRuleDtos.CreateRuleItem;
import com.lumen.finance.dto.BillingRuleDtos.View;
import com.lumen.finance.entity.BillingRule;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingRuleMapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BillingRuleService 集成测试 (Iteration 4.3.1, PR3-T3.1).
 *
 * <p>覆盖 3 个核心端到端场景:
 * <ul>
 *   <li>create + activate 完整成功路径 (DB 落库验证 status=ACTIVE, items 数)</li>
 *   <li>activate 表达式非法 → status=INVALID 落库 + EXPRESSION_INVALID 抛出</li>
 *   <li>相同 UNIQUE KEY 重复创建 → DUPLICATE_RULE</li>
 * </ul>
 *
 * <p>每个测试用独占的 tenantId (从 {@link #TENANT_SEQ} 拿) 避免跨测试数据干扰.
 * 末尾清理 {@link TenantContext}, 与 lumen-cs CsConfigIT 模式一致.
 */
@DisplayName("BillingRuleActivation IT")
class BillingRuleActivationIT extends IntegrationBase {

    @Autowired private BillingRuleService billingRuleService;
    @Autowired private BillingRuleMapper billingRuleMapper;

    /**
     * Counter-based unique tenantId generator. 起始 200_000L 避开 lumen-cs IT 占用段 (100_000+).
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(200_000L);
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
    @DisplayName("BillingRule: create + activate 完整成功路径")
    void createAndActivate_happyPath() {
        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_CUSTOMER,
            FinanceConstants.CURRENCY_CNY, 100,
            LocalDate.of(2026, 10, 1), null, "IT happy path",
            List.of(
                new CreateRuleItem(1, "BASE",    "amount * 1.0", null, "gross_amount", null),
                new CreateRuleItem(2, "WEIGHT",  "weight * 2",   null, "gross_amount", null)
            )
        );

        View created = billingRuleService.create(tenantId, 1L, req);
        assertThat(created.id()).isNotNull();
        assertThat(created.status()).isEqualTo(FinanceConstants.RULE_STATUS_DRAFT);

        View activated = billingRuleService.activate(tenantId, created.id(), 1L);
        assertThat(activated.status()).isEqualTo(FinanceConstants.RULE_STATUS_ACTIVE);
        assertThat(activated.items()).hasSize(2);
        // expressionHash 应当都按 SHA-256 64-hex 填充
        assertThat(activated.items().get(0).expressionHash())
            .isEqualTo(com.lumen.finance.engine.BillingScriptEngine.normalizeHash("amount * 1.0"));
        assertThat(activated.items().get(0).expressionHash()).hasSize(64);

        // 回读 DB 确认状态 (MyMetaObjectHandler + updateById 走通)
        BillingRule dbRule = billingRuleMapper.selectById(created.id());
        assertThat(dbRule).isNotNull();
        assertThat(dbRule.getStatus()).isEqualTo(FinanceConstants.RULE_STATUS_ACTIVE);
    }

    @Test
    @DisplayName("BillingRule: activate 表达式非法 → status=INVALID + EXPRESSION_INVALID")
    void activate_invalidExpression_throwsAndSetsInvalid() {
        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_PAYABLE,
            FinanceConstants.CP_CARRIER,
            FinanceConstants.CURRENCY_CNY, 200,
            LocalDate.of(2026, 10, 1), null, "IT invalid expr",
            List.of(
                new CreateRuleItem(1, "BASE", "amount * 1.0", null, "gross_amount", null),
                new CreateRuleItem(2, "WEIGHT", "1 +", null, "gross_amount", null)   // 语法错误
            )
        );
        View created = billingRuleService.create(tenantId, 1L, req);
        Long ruleId = created.id();

        assertThatThrownBy(() -> billingRuleService.activate(tenantId, ruleId, 1L))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.EXPRESSION_INVALID);

        // @TableLogic select=false 故 selectById 自动过滤 deleted=0 行; 这里应是 DRAFT 状态
        // (因为 service 是在 setInvalid 之前已经判断该状态). 让我们看 service 逻辑:
        // service.activate 在 item 编译失败时 setStatus=INVALID + updateById + 抛异常.
        // 所以 DB 里 status=INVALID.
        BillingRule dbRule = billingRuleMapper.selectById(ruleId);
        assertThat(dbRule).isNotNull();
        assertThat(dbRule.getStatus()).isEqualTo(FinanceConstants.RULE_STATUS_INVALID);
    }

    @Test
    @DisplayName("BillingRule: 相同 UNIQUE KEY 重复创建 → DUPLICATE_RULE")
    void duplicateRule_throwsDuplicateRule() {
        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_WAREHOUSE_IN,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_WAREHOUSE,
            FinanceConstants.CURRENCY_CNY, 100,
            LocalDate.of(2026, 10, 15), null, "IT duplicate",
            List.of(new CreateRuleItem(1, "BASE", "1.0", null, "gross_amount", null))
        );

        View first = billingRuleService.create(tenantId, 1L, req);
        assertThat(first.id()).isNotNull();

        assertThatThrownBy(() -> billingRuleService.create(tenantId, 1L, req))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.DUPLICATE_RULE);
    }
}
