package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingEventDto;
import com.lumen.finance.service.BillingEngineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 4.5D-1 —— 计费失败取证链路的端到端 IT.
 *
 * <p>被测的契约(设计文档 §2.2):计费规则求值失败时, 系统必须留下<b>失败取证</b>:
 * <ul>
 *   <li>{@code t_billing_accrual} 写一行 {@code OPEN_FAILED}(金额 0, 供后台 job 重试)</li>
 *   <li>{@code t_billing_event_log} 标 {@code FAILED}(仅 accrue 阶段失败时)</li>
 * </ul>
 * 4.5D-1 之前这条链路是断的: 订单照常提交, 账上零行痕迹(1364 —— 已由 V41 把
 * {@code t_billing_accrual.rule_id} 改可空修掉); accrue 阶段的失败取证还会挂 50 秒
 * (REQUIRES_NEW 内层事务等外层未提交行的行锁 —— 已由本轮把失败取证拆成两段写修掉:
 * event_log 的 FAILED 标记回到 accrue 自己的事务里, 只有 accrual 的 OPEN_FAILED 行
 * 留在 REQUIRES_NEW)。
 *
 * <p>本类继承 {@link PortalOrderFixtureBase} 而不是 {@link IntegrationBase} ——
 * 因为需要 fixture 里现成的 {@code seedActiveLastMileRule(expression)}:
 * 它直接用 JdbcTemplate 插 ACTIVE rule,
 * <b>绕过</b> {@code BillingRuleService.activate} 的表达式校验(activate 会拒收坏表达式,
 * 见 {@code BillingRuleActivationIT})。不这样就种不出「会求值失败」的规则。
 *
 * <p>本类的两条用例<b>刻意用不同的 override 布局</b>来把失败点钉在不同阶段:
 * 第 1 条<b>不</b> seed override —— {@code estimate} 真正去求值规则表达式, 失败落在
 * estimate 阶段(accrue 根本没被调用); 第 2 条 seed override —— {@code estimate} 走短路成功,
 * 失败落在 accrue 阶段。两条合起来才覆盖「失败取证」的两种落点。
 *
 * <p>每条用例独占 DO id, teardown 靠 {@code business_id} / {@code source_event_id} 清 accrual
 * 与 event_log.
 */
@DisplayName("计费失败取证 IT (4.5D-1)")
class BillingFailureAuditIT extends PortalOrderFixtureBase {

    @Autowired
    private BillingEngineService billingEngineService;

    @Autowired
    private PlatformTransactionManager txManager;

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
        // 这里不 seed override —— 没有 override 时 estimate 会真正去求值规则表达式,
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
            "SELECT status, gross_amount, tax_amount, net_amount, rule_id, source_event_id, "
                + "counterparty_id, error_msg "
                + "FROM t_billing_accrual WHERE business_id = ? AND deleted = 0", doId);
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
        assertThat(row.get("source_event_id"))
            .as("source_event_id 是幂等键, 也是后台 job 找回这行的依据")
            .isEqualTo(String.valueOf(doId));
        assertThat(((Number) row.get("counterparty_id")).longValue())
            .as("counterparty_id 错则对账口径错")
            .isEqualTo(customer42Id);
        assertThat((String) row.get("error_msg")).isNotBlank();

        // estimate 阶段就失败了, accrue 从未被调用 → event_log 一行都没有
        assertThat(jdbc.queryForList(
                "SELECT id FROM t_billing_event_log WHERE source_event_id = ? AND deleted = 0",
                String.valueOf(doId)))
            .as("estimate 阶段失败时 event_log 还没有行 (accrue 没被调用)")
            .isEmpty();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("accrue 阶段求值失败 → event_log=FAILED + accrual=OPEN_FAILED, 且不挂起")
    void accrueStageFailure_writesFailedEventLogAndOpenFailedAccrual_withoutHanging() {
        // seed override → estimate 走 override 短路, 求值不失败;
        // 同时 seed 一条坏表达式 rule → accrue 走 rule 路径求值失败。
        // 这条组合是「accrue 阶段失败」的唯一入口: 没有 override 时 estimate 先失败,
        // accrue 根本轮不到 (见上面那条用例)。
        seedRateOverride(customer42Id, new BigDecimal("88.0000"));
        long ruleId = seedActiveLastMileRule("unknown_var * 2");

        long doId = seedDeliveryOrder(customer42Id, "PENDING_DISPATCH");
        BillingEventDto event = eventOf(doId);

        // **必须**套一个外层事务 —— 这才复现生产里的锁等待场景:
        // 外层事务里 event_log 的 INSERT 未提交、持 X 行锁 → REQUIRES_NEW 的
        // UPDATE 要等它 → innodb_lock_wait_timeout。
        // 没有外层事务时 (裸调 service) INSERT 会自动提交, 根本不会冲突。
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> billingEngineService.estimateAndAccrue(event));

        // event_log: FAILED
        List<Map<String, Object>> logs = jdbc.queryForList(
            "SELECT process_status, error_msg FROM t_billing_event_log WHERE source_event_id = ? "
                + "AND deleted = 0",
            String.valueOf(doId));
        assertThat(logs)
            .as("accrue 阶段失败必须留下 1 行 FAILED 事件日志 —— 4.5D-1 之前这行是 RECEIVED")
            .hasSize(1);
        assertThat(logs.get(0).get("process_status")).isEqualTo(FinanceConstants.EVT_STATUS_FAILED);
        assertThat((String) logs.get(0).get("error_msg")).isNotBlank();

        // accrual: OPEN_FAILED + rule_id 非空 (accrue 阶段知道命中了哪条规则)
        List<Map<String, Object>> accruals = jdbc.queryForList(
            "SELECT status, gross_amount, rule_id, error_msg FROM t_billing_accrual "
                + "WHERE business_id = ? AND deleted = 0",
            doId);
        assertThat(accruals)
            .as("accrue 阶段失败必须留下 1 行 OPEN_FAILED 取证行")
            .hasSize(1);
        Map<String, Object> row = accruals.get(0);
        assertThat(row.get("status")).isEqualTo(FinanceConstants.ACCRUAL_STATUS_OPEN_FAILED);
        assertThat((BigDecimal) row.get("gross_amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(((Number) row.get("rule_id")).longValue())
            .as("accrue 阶段知道命中了哪条规则 → rule_id 必须有值")
            .isEqualTo(ruleId);
        assertThat((String) row.get("error_msg")).isNotBlank();
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
