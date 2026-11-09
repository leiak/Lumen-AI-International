package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingEventDto;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4.5D-1 —— 计费失败取证链路的端到端 IT.
 *
 * <p>被测的契约(设计文档 §2.2):计费规则求值失败时, 系统必须留下<b>失败取证</b>:
 * <ul>
 *   <li>{@code t_billing_accrual} 写一行 {@code OPEN_FAILED}(金额 0, 供将来补单/重试使用)</li>
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
 * <p>本类的失败取证用例<b>刻意用不同的 override 布局</b>来把失败点钉在不同阶段:
 * estimate 阶段那条<b>不</b> seed override —— {@code estimate} 真正去求值规则表达式, 失败落在
 * estimate 阶段(accrue 根本没被调用); accrue 阶段那条 seed override —— {@code estimate} 走短路成功,
 * 失败落在 accrue 阶段。两条合起来才覆盖「失败取证」的两种落点。
 *
 * <p>每条用例独占 DO id, teardown 靠 {@code business_id} / {@code source_event_id} 清 accrual
 * 与 event_log.
 *
 * <p><b>回归网</b>(4.5D-1 Task 4 追加的 3 条) —— 它们不验新功能, 只确认「修失败路径时
 * 没把成功路径带坏」:
 * <ul>
 *   <li>成功路径: 正常规则 → event_log=SUCCESS + accrual=OPEN, gross = weight*2 = 20</li>
 *   <li>原子性: 外层事务回滚 → event_log 与 accrual 都不留行(成功分支的三次写入必须同生共死;
 *       它守的是「没被人为改成 REQUIRES_NEW」—— 失败分支的 OPEN_FAILED 取证行故意独立事务,
 *       那是另一回事)。<b>这条用例是三段断言, 不只是两条 isEmpty()</b>: 事务回调**内部**先断言
 *       两行**确实已经写进库**了(未提交, 但对事务自己的连接可见), 回滚**之后**才断言查不到行 ——
 *       少了事务内那一步, 「回滚后为空」就可能是因为「压根没写进去」而空洞地通过, 守不住
 *       「写了一半」。正因为事务内那两条断言已经证明了「写过」, 外层就不需要任何 try/catch:
 *       回滚是静默的, 断言失败会由 TransactionTemplate 原样 rethrow)</li>
 *   <li>listener 路径(路径 2, 此前无任何 IT 覆盖): 直接调 {@code accrue} → 它自己的
 *       {@code @Transactional} 开新事务 → 求值失败抛 FinanceException → 事务回滚 →
 *       event_log 不留行, 但失败取证那半在 REQUIRES_NEW 里独立提交 → OPEN_FAILED 取证行活下来。
 *       {@code @Timeout(30s)} 是核心: 4.5D-1 之前失败取证要在 REQUIRES_NEW 里 UPDATE 外层
 *       未提交的那行 event_log(持 X 行锁) → 等 innodb_lock_wait_timeout(50s)</li>
 * </ul>
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

        // estimateAndAccrue 的契约是「失败不抛」(DO 创建是关键路径, finance 侧失败只留取证行)。
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
            .as("estimate 阶段失败必须留下 1 行 OPEN_FAILED 取证行 —— 否则账上零痕迹, 将来无从补单")
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
            .as("source_event_id 是幂等键, 也是将来找回这行的依据")
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
        // override 的金额没有意义 —— 它只影响 estimate (走 SOURCE_OVERRIDE 短路),
        // accrue 仍走规则求值, 才走到「accrue 阶段失败」那条路。给 ONE 只是为了不误导。
        seedRateOverride(customer42Id, BigDecimal.ONE);
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

    @Test
    @DisplayName("正常规则 → event_log=SUCCESS + accrual=OPEN, 金额 = weight*2 = 20")
    void successPath_writesOpenAccrualAndSuccessEventLog() {
        // 没有 override → estimate 真正去求值规则表达式 → gross = weight * 2 = 10 * 2 = 20
        // (tax_amount 恒为 0 —— 4.3.2 的 placeholder; net_amount = gross)
        seedActiveLastMileRule("weight * 2");

        long doId = seedDeliveryOrder(customer42Id, "PENDING_DISPATCH");
        billingEngineService.estimateAndAccrue(eventOf(doId));

        List<Map<String, Object>> logs = jdbc.queryForList(
            "SELECT process_status FROM t_billing_event_log WHERE source_event_id = ? AND deleted = 0",
            String.valueOf(doId));
        assertThat(logs).as("成功路径必须留 1 行事件日志").hasSize(1);
        assertThat(logs.get(0).get("process_status")).isEqualTo(FinanceConstants.EVT_STATUS_SUCCESS);

        List<Map<String, Object>> accruals = jdbc.queryForList(
            "SELECT status, gross_amount, tax_amount, net_amount FROM t_billing_accrual "
                + "WHERE business_id = ? AND deleted = 0", doId);
        assertThat(accruals).as("成功路径必须留 1 行 OPEN accrual").hasSize(1);
        Map<String, Object> row = accruals.get(0);
        assertThat(row.get("status")).isEqualTo(FinanceConstants.ACCRUAL_STATUS_OPEN);
        assertThat((BigDecimal) row.get("gross_amount")).isEqualByComparingTo(new BigDecimal("20"));
        assertThat((BigDecimal) row.get("tax_amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) row.get("net_amount")).isEqualByComparingTo(new BigDecimal("20"));
    }

    @Test
    @DisplayName("外层事务回滚 → event_log 与 accrual 都不留行 (成功分支的三次写入必须同生共死)")
    void rolledBackOuterTransaction_leavesNoEventLogAndNoAccrual() {
        seedActiveLastMileRule("weight * 2");

        long doId = seedDeliveryOrder(customer42Id, "PENDING_DISPATCH");
        BillingEventDto event = eventOf(doId);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            billingEngineService.estimateAndAccrue(event);

            // 事务内查: 这两行此时**确实已经写进库**了 (JdbcTemplate 复用事务的那条
            // 连接, 未提交的写入对本连接可见)。
            //
            // 少了这一步, 下面「回滚后查不到行」的断言就可能是因为「压根没写进去」
            // 而空洞地通过 —— 那样它守不住「写了一半」这件事。
            // 注意: assertThat 抛的是 AssertionError (Error, 不是 RuntimeException),
            // TransactionTemplate.execute 会原样 rethrow 它 → 直接让用例红。
            assertThat(jdbc.queryForList(
                    "SELECT id FROM t_billing_event_log WHERE source_event_id = ? AND deleted = 0",
                    String.valueOf(doId)))
                .as("事务内: event_log 已经写入 (未提交) —— 证明下面断言的「回滚后为空」是真回滚")
                .hasSize(1);
            assertThat(jdbc.queryForList(
                    "SELECT id FROM t_billing_accrual WHERE business_id = ? AND deleted = 0", doId))
                .as("事务内: accrual 已经写入 (未提交) —— 证明下面断言的「回滚后为空」是真回滚")
                .hasSize(1);

            status.setRollbackOnly();
        });
        // 最外层 status.setRollbackOnly() 在 commit 时是**静默回滚**(Spring 6.1.6
        // AbstractPlatformTransactionManager.commit() → isLocalRollbackOnly() → processRollback(false)),
        // 不抛任何异常 —— 故这里不需要 try/catch。

        assertThat(jdbc.queryForList(
                "SELECT id FROM t_billing_event_log WHERE source_event_id = ? AND deleted = 0",
                String.valueOf(doId)))
            .as("外层事务回滚后 event_log 不该有行")
            .isEmpty();
        assertThat(jdbc.queryForList(
                "SELECT id FROM t_billing_accrual WHERE business_id = ? AND deleted = 0", doId))
            .as("外层事务回滚后 accrual 不该有行")
            .isEmpty();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("listener 路径 (accrue 独自开事务) 求值失败 → 抛 FinanceException, event_log 回滚但 OPEN_FAILED 取证行活下来")
    void listenerPath_accrueFailure_rollsBackEventLogButKeepsEvidenceRow() {
        // 复刻生产里的路径 2: BusinessEventListener 跨 bean 调 engine.accrue(dto) →
        // 走 Spring 代理 → accrue 自己的 @Transactional 开新事务 → 求值失败抛
        // FinanceException → 该事务回滚。event_log 的行随之回滚(消失), 但失败取证
        // 那半在 REQUIRES_NEW 里, 已经独立提交 → 活下来。
        //
        // @Timeout 是这条用例的核心: 4.5D-1 之前, 失败取证要在 REQUIRES_NEW 里 UPDATE
        // 外层未提交的那行 event_log (它持着 X 行锁) → 等 innodb_lock_wait_timeout
        // (默认 50s)。这条用例在 4.5D-1 之前会挂 50 秒然后超时 —— 它就是「50 秒
        // 锁等待」的回归网。
        //
        // 注意: 不要给 @Timeout 加 threadMode —— SEPARATE_THREAD 会让 @BeforeEach 里
        // 设的 TenantContext (纯 ThreadLocal) 落在别的线程上而失效, 而且也停不掉
        // JDBC 的锁等待。
        long ruleId = seedActiveLastMileRule("unknown_var * 2");

        long doId = seedDeliveryOrder(customer42Id, "PENDING_DISPATCH");

        assertThatThrownBy(() -> billingEngineService.accrue(eventOf(doId)))
            .as("accrue 的契约: 求值失败抛 FinanceException(EXPRESSION_INVALID)")
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.EXPRESSION_INVALID);

        // event_log 回滚 → 没有行
        assertThat(jdbc.queryForList(
                "SELECT id FROM t_billing_event_log WHERE source_event_id = ? AND deleted = 0",
                String.valueOf(doId)))
            .as("accrue 抛异常 → 它自己那个事务回滚 → event_log 不留行")
            .isEmpty();

        // 取证行在 REQUIRES_NEW 里独立提交 → 活下来
        List<Map<String, Object>> accruals = jdbc.queryForList(
            "SELECT status, gross_amount, error_msg, rule_id, source_event_id FROM t_billing_accrual "
                + "WHERE business_id = ? AND deleted = 0", doId);
        assertThat(accruals)
            .as("失败取证行在 REQUIRES_NEW 里独立提交, 必须活过外层回滚")
            .hasSize(1);
        assertThat(accruals.get(0).get("status"))
            .isEqualTo(FinanceConstants.ACCRUAL_STATUS_OPEN_FAILED);
        assertThat((BigDecimal) accruals.get(0).get("gross_amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((String) accruals.get(0).get("error_msg")).isNotBlank();
        // 取证行必须属于「本次事件 + 本次命中的规则」—— 否则它可能只是碰巧存在的一行旧取证,
        // 「留痕」就留错了对象 (兄弟用例 accrueStageFailure_... 已断言 rule_id 非空)。
        assertThat(((Number) accruals.get(0).get("rule_id")).longValue())
            .as("accrue 阶段已经查到命中的规则 → 取证行的 rule_id 必须有值且指向那条规则")
            .isEqualTo(ruleId);
        assertThat(accruals.get(0).get("source_event_id"))
            .as("source_event_id 是幂等键, 必须等于本次事件的 doId —— 证明取证行属于本次事件")
            .isEqualTo(String.valueOf(doId));
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
