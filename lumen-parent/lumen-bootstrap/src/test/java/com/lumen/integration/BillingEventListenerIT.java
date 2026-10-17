package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.event.DeliveryOrderStateChangedEvent;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingRuleDtos.CreateRequest;
import com.lumen.finance.dto.BillingRuleDtos.CreateRuleItem;
import com.lumen.finance.dto.BillingRuleDtos.View;
import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.entity.BillingEventLog;
import com.lumen.finance.mapper.BillingAccrualMapper;
import com.lumen.finance.mapper.BillingEventLogMapper;
import com.lumen.finance.service.BillingEngineService;
import com.lumen.finance.service.BillingRuleService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BusinessEventListener 端到端集成测试 (Iteration 4.3.1, PR3-T3.2).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. 通过 {@link ApplicationEventPublisher}
 * 注入一个 DeliveryOrderStateChangedEvent → 走 outbox listener (异步 outboxExecutor) →
 * 触发 BillingEngineService.accrue → 写 event_log + accrual.
 *
 * <p>核心场景:
 * <ul>
 *   <li>publish 一条 SIGNED 事件 → 等 accrual 落库 (event_log + accrual 各 1 行)</li>
 *   <li>publish 同 source eventId 重复事件 → accrual 仍是 1 行 (幂等)</li>
 * </ul>
 *
 * <p>每个测试独占 tenantId (TENANT_SEQ), 避开其他 IT 数据.
 */
@DisplayName("BillingEventListener IT")
class BillingEventListenerIT extends IntegrationBase {

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private BillingRuleService billingRuleService;
    @Autowired private BillingEngineService billingEngineService;
    @Autowired private BillingEventLogMapper eventLogMapper;
    @Autowired private BillingAccrualMapper accrualMapper;

    private static final AtomicLong TENANT_SEQ = new AtomicLong(300_000L);
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
    @DisplayName("BusinessEventListener: SIGNED 事件 → event_log + accrual 双行, 重发幂等")
    void listener_accruesBillingForSignedDeliveryOrderEvent() {
        // 1. 先建 ACTIVE 规则: base 10 + weight*2
        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_CUSTOMER,
            FinanceConstants.CURRENCY_CNY, 100,
            LocalDate.of(2026, 10, 1), null, "IT listener rule",
            List.of(
                new CreateRuleItem(1, "BASE",    "10",         null, "gross_amount", null),
                new CreateRuleItem(2, "WEIGHT",  "weight*2",   null, "gross_amount", null)
            )
        );
        View created = billingRuleService.create(tenantId, 1L, req);
        billingRuleService.activate(tenantId, created.id(), 1L);

        // 2. publish 一条 SIGNED 事件 (toState="SIGNED")
        DeliveryOrderStateChangedEvent event = new DeliveryOrderStateChangedEvent(
            tenantId,                  // tenantId
            88888L,                    // orderId
            "DO-20261017-001",         // no
            "ENROUTE",                 // fromState
            "SIGNED",                  // toState  (触发计费)
            "sign",                    // action
            1L,                        // vehicleId
            2L,                        // driverId
            new BigDecimal("5.0"),     // totalWeightKg → env.weight = 5
            new BigDecimal("1.0"),     // totalVolumeM3
            2,                         // stopCount   → env.quantity = 2
            null,                      // exceptionId
            "system"                   // actor
        );

        publisher.publishEvent(event);

        // 3. 等 listener 处理完 (异步 outboxExecutor 线程) — 等 accrual 行出现
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                TenantContext.set(tenantId);  // outbox 线程结束后 TenantContext 已清, 重设供查询
                assertThat(accrualMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BillingAccrual>()
                        .eq(BillingAccrual::getTenantId, tenantId)
                        .eq(BillingAccrual::getSourceEventId, event.getEventId())
                )).isEqualTo(1L);
            });

        // 4. 验证 event_log + accrual 内容
        TenantContext.set(tenantId);
        BillingEventLog log = eventLogMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BillingEventLog>()
                .eq(BillingEventLog::getTenantId, tenantId)
                .eq(BillingEventLog::getSourceEventId, event.getEventId())
        );
        assertThat(log).isNotNull();
        assertThat(log.getProcessStatus()).isEqualTo(FinanceConstants.EVT_STATUS_SUCCESS);
        assertThat(log.getProcessedAt()).isNotNull();

        BillingAccrual acc = accrualMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BillingAccrual>()
                .eq(BillingAccrual::getTenantId, tenantId)
                .eq(BillingAccrual::getSourceEventId, event.getEventId())
        );
        assertThat(acc).isNotNull();
        assertThat(acc.getStatus()).isEqualTo(FinanceConstants.ACCRUAL_STATUS_OPEN);
        // gross = base(10) + weight*2(5*2=10) = 20
        assertThat(acc.getGrossAmount()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(acc.getTaxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(acc.getNetAmount()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(acc.getCurrency()).isEqualTo(FinanceConstants.CURRENCY_CNY);
        assertThat(acc.getRuleId()).isEqualTo(created.id());
        assertThat(acc.getBusinessId()).isEqualTo(88888L);

        // 5. 重发同 eventId (DomainEvent.eventId 由 UUID 生成, 同一 instance 即同一 id) — 幂等校验
        // 注意: DomainEvent.eventId 是 final, 同一对象重发 eventId 不变 → 应被 event_log UNIQUE 拦截.
        // 但因为 listener @Async 用同一对象引用, eventId 仍是原始 UUID.
        publisher.publishEvent(event);

        // 等第二波 listener 处理 (即使会被 skip) — Awaitility 比 Thread.sleep 稳, 避免假阴性.
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                TenantContext.set(tenantId);
                long accrualCount = accrualMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BillingAccrual>()
                        .eq(BillingAccrual::getTenantId, tenantId)
                        .eq(BillingAccrual::getSourceEventId, event.getEventId())
                );
                assertThat(accrualCount).isEqualTo(1L);
            });
    }
}
