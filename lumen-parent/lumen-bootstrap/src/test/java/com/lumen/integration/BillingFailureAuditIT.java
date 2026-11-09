package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingEventDto;
import com.lumen.finance.service.BillingEngineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 4.5D-1 —— 计费失败取证链路的端到端 IT.
 *
 * <p>被测的契约(设计文档 §2.2):计费规则求值失败时, 系统必须留下**失败取证**:
 * <ul>
 *   <li>{@code t_billing_accrual} 写一行 {@code OPEN_FAILED}(金额 0, 供后台 job 重试)</li>
 *   <li>{@code t_billing_event_log} 标 {@code FAILED}(仅 accrue 阶段失败时; 见下面第 2 条用例)</li>
 * </ul>
 * 4.5D-1 之前这条链路是断的: 订单照常提交, 账上零行痕迹(1364),
 * 且 accrue 阶段的失败取证会挂 50 秒(REQUIRES_NEW 内层事务等外层未提交行的行锁).
 *
 * <p>本类继承 {@link PortalOrderFixtureBase} 而不是 {@link IntegrationBase} ——
 * 因为需要两样 fixture 里现成的东西:
 * <ul>
 *   <li>{@code seedActiveLastMileRule(expression)} —— 直接用 JdbcTemplate 插 ACTIVE rule,
 *       <b>绕过</b> {@code BillingRuleService.activate} 的表达式校验(activate 会拒收坏表达式,
 *       见 {@code BillingRuleActivationIT})。不这样就种不出「会求值失败」的规则。</li>
 *   <li>{@code seedRateOverride(customerId, amount)} —— 让 {@code estimate} 走 override
 *       短路, 从而把失败点从 estimate 阶段推到 accrue 阶段。</li>
 * </ul>
 *
 * <p>每条用例独占 DO id, teardown 靠 {@code business_id} / {@code source_event_id} 清 accrual
 * 与 event_log.
 */
@DisplayName("计费失败取证 IT (4.5D-1)")
class BillingFailureAuditIT extends PortalOrderFixtureBase {

    @Autowired
    private BillingEngineService billingEngineService;

    @BeforeEach
    void setUpTenant() {
        TenantContext.set(1L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("estimate 阶段求值失败 → 写 1 行 OPEN_FAILED accrual (rule_id 为 NULL), 且不抛异常")
    void estimateStageFailure_writesOpenFailedAccrual() {
        // 坏表达式: 引用白名单外的变量 → BillingScriptEngine.compile 阶段校验抛
        // FinanceException_UnknownVar (白名单 9 个变量, 见 BillingScriptEngine).
        // 这里**不** seed override —— 没有 override 时 estimate 会真正去求值规则表达式,
        // 于是失败发生在 estimate 阶段 (accrue 根本没被调用)。
        seedActiveLastMileRule("unknown_var * 2");

        long doId = seedDeliveryOrder(customer42Id, "PENDING_DISPATCH");

        // estimateAndAccrue 的契约是「失败不抛」(DO 创建是关键路径, finance 失败由后台 job 重试)。
        // 4.5D-1 之前这里会抛 DataIntegrityViolationException(1364) —— 取证行写不进去,
        // 异常从 catch 块里又逃了出来。
        assertThatCode(() -> billingEngineService.estimateAndAccrue(eventOf(doId)))
            .as("estimateAndAccrue 的契约是失败不抛 —— 取证写库的异常不能顶给调用方")
            .doesNotThrowAnyException();

        List<Map<String, Object>> accruals = jdbc.queryForList(
            "SELECT status, gross_amount, tax_amount, net_amount, rule_id, error_msg "
                + "FROM t_billing_accrual WHERE business_id = ?", doId);
        assertThat(accruals)
            .as("estimate 阶段失败必须留下 1 行 OPEN_FAILED 取证行 —— 否则账上零痕迹, 后台 job 无从重试")
            .hasSize(1);
        Map<String, Object> row = accruals.get(0);
        assertThat(row.get("status")).isEqualTo(FinanceConstants.ACCRUAL_STATUS_OPEN_FAILED);
        assertThat((BigDecimal) row.get("gross_amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) row.get("tax_amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) row.get("net_amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.get("rule_id"))
            .as("estimate 阶段失败时规则归属未知 → rule_id 允许为 NULL (V41 起)")
            .isNull();
        assertThat((String) row.get("error_msg")).isNotBlank();

        // estimate 阶段就失败了, accrue 从未被调用 → event_log 一行都没有
        assertThat(jdbc.queryForList(
                "SELECT id FROM t_billing_event_log WHERE source_event_id = ?",
                String.valueOf(doId)))
            .as("estimate 阶段失败时 event_log 还没有行 (accrue 没被调用)")
            .isEmpty();
    }

    /** 构造一个与 {@code DeliveryOrderServiceImpl.createForCustomer} 同形状的计费事件。 */
    private BillingEventDto eventOf(long doId) {
        return new BillingEventDto(
            1L,                             // tenantId
            String.valueOf(doId),            // sourceEventId = doId (幂等键)
            FinanceConstants.BIZ_LAST_MILE,
            doId,                           // businessId
            FinanceConstants.DIR_RECEIVABLE,
            customer42Id,                   // counterpartyId
            FinanceConstants.CP_CUSTOMER,
            Map.<String, Object>of(
                "weight", new BigDecimal("10"),
                "quantity", 1,
                "distance", BigDecimal.ZERO));
    }
}
