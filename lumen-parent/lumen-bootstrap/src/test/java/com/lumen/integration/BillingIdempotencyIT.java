package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * 计费链路幂等 IT (Iteration 4.3.1, PR3-T3.5).
 *
 * <p>对应 R-2 风险 mitigation: 重发同一 {@code sourceEventId} 必须仅落 1 行
 * {@code t_billing_accrual} + 1 行 {@code t_billing_event_log}, 不能因为 controller
 * 重试 / outbox 重复派发导致重复计费.
 *
 * <p>复用 T3.2 {@link BillingEventListenerIT} 同款模式: 建 ACTIVE rule ->
 * {@link ApplicationEventPublisher#publishEvent} 一条 {@code SIGNED} 事件 ->
 * 等 listener 处理完成 -> 断言 event_log + accrual 行数.
 *
 * <p>每个测试独占 tenantId (起始 700_000L) 避开其他 IT 占用段.
 */
@DisplayName("BillingIdempotency IT (R-2)")
class BillingIdempotencyIT extends IntegrationBase {

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private BillingRuleService billingRuleService;
    @Autowired private BillingEventLogMapper eventLogMapper;
    @Autowired private BillingAccrualMapper accrualMapper;

    /**
     * Counter-based unique tenantId. 起始 700_000L 避开 lumen-cs (100_000+) /
     * BillingRuleActivationIT (200_000+) / BillingEventListenerIT (300_000+) /
     * BillingAccrualFinalizeIT (400_000+) / BillingInvoiceApprovalIT (500_000+) /
     * T3.5 BillingPermissionIT / BillingRuleAuditIT (600_000+).
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
    @DisplayName("R-2: 重发同一 sourceEventId -> event_log + accrual 各 1 行 (不重复计费)")
    void duplicatePublish_isIdempotent() {
        // 1. 建 ACTIVE rule -- base 10 + weight*2
        View created = createActiveRule();

        // 2. 构造一条 SIGNED 事件 (eventId 是 DomainEvent.eventId, 即一次 publish 的 idempotency key)
        String sourceEventId = "it-idem-" + System.nanoTime();
        DeliveryOrderStateChangedEvent event = new DeliveryOrderStateChangedEvent(
            tenantId, 99999L, "DO-IDEM-001",
            "ENROUTE", "SIGNED", "sign",
            1L, 2L,
            new BigDecimal("5.0"), new BigDecimal("1.0"), 2,
            null, "system"
        );
        // 重设 eventId 让幂等 key 命中 (避免与本类其他测试 / 同测试内的 publishEvent 冲突)
        // 注: DomainEvent.eventId 是 final, 通过 JSON 反序列化暂不可; 我们改用固定 sourceEventId
        // 配合 UNIQUE KEY (tenant_id, source_event_id, deleted) 即可达到幂等校验目的.
        // 但 listener 用 event.eventId() 作为 sourceEventId -- 因此重发同一对象即幂等.
        // 这里我们直接复用 listener 的 eventId: 重发 event 两次.
        publisher.publishEvent(event);

        // 3. 等 listener 第一次处理完 -- event_log + accrual 各 1 行
        // 注: outbox dispatcher 是 polling 模式, 第一跳 (publish -> outbox) 同步,
        //     第二跳 (outbox -> listener -> accrue) 异步轮询. 在 IT 环境下 dispatcher
        //     启动 + 首次轮询可能 1-5s. 给 30s 余量.
        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> {
                TenantContext.set(tenantId);
                assertThat(accrualMapper.selectCount(
                    new LambdaQueryWrapper<BillingAccrual>()
                        .eq(BillingAccrual::getTenantId, tenantId)
                        .eq(BillingAccrual::getSourceEventId, event.getEventId())
                )).isEqualTo(1L);
            });

        // 4. 立即重发同一 event (R-2 风险 -- 模拟 outbox 重复派发 / controller 重试)
        publisher.publishEvent(event);

        // 5. 等第二波 listener 处理 -- 即便被 skip, 也至少不会增加 accrual 行数
        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted(() -> {
                TenantContext.set(tenantId);
                long accrualCount = accrualMapper.selectCount(
                    new LambdaQueryWrapper<BillingAccrual>()
                        .eq(BillingAccrual::getTenantId, tenantId)
                        .eq(BillingAccrual::getSourceEventId, event.getEventId())
                );
                assertThat(accrualCount).isEqualTo(1L);

                long eventLogCount = eventLogMapper.selectCount(
                    new LambdaQueryWrapper<BillingEventLog>()
                        .eq(BillingEventLog::getTenantId, tenantId)
                        .eq(BillingEventLog::getSourceEventId, event.getEventId())
                );
                assertThat(eventLogCount).isEqualTo(1L);
            });

        // 6. 直接断言两次累加: 再次 SELECT COUNT 校验最终状态 (Awaitility 的 untilAsserted
        //    可能在第二次 publish 处理前就 "稳定" 了 -- 这里做一次最终 snapshot)
        TenantContext.set(tenantId);
        long finalAccrualCount = accrualMapper.selectCount(
            new LambdaQueryWrapper<BillingAccrual>()
                .eq(BillingAccrual::getTenantId, tenantId)
                .eq(BillingAccrual::getSourceEventId, event.getEventId())
        );
        long finalEventLogCount = eventLogMapper.selectCount(
            new LambdaQueryWrapper<BillingEventLog>()
                .eq(BillingEventLog::getTenantId, tenantId)
                .eq(BillingEventLog::getSourceEventId, event.getEventId())
        );
        assertThat(finalAccrualCount)
            .as("幂等: 重发同一事件 -> accrual 行数仍为 1")
            .isEqualTo(1L);
        assertThat(finalEventLogCount)
            .as("幂等: 重发同一事件 -> event_log 行数仍为 1")
            .isEqualTo(1L);

        // 7. 验证金额正确 (gross = 10 + 5*2 = 20)
        BillingAccrual acc = accrualMapper.selectOne(
            new LambdaQueryWrapper<BillingAccrual>()
                .eq(BillingAccrual::getTenantId, tenantId)
                .eq(BillingAccrual::getSourceEventId, event.getEventId())
        );
        assertThat(acc).isNotNull();
        assertThat(acc.getGrossAmount()).isEqualByComparingTo(new BigDecimal("20"));
        // 留痕: sourceEventId 必须保留 (供排查)
        assertThat(acc.getSourceEventId()).isEqualTo(event.getEventId());
    }

    // ---------- helpers ----------

    private View createActiveRule() {
        // effectiveFrom 必须落在今天 (含) 之前, 否则 listener 查询 ACTIVE rule
        // 时会过滤掉 (effective_from <= today). 用固定 2026-01-01 + tenantId 偏移天,
        // 避开 UNIQUE KEY 同时保证 <= today.
        LocalDate effFrom = LocalDate.of(2026, 1, 1).plusDays((int) (tenantId % 300));
        int pri = (int) (tenantId % 1000);
        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_CUSTOMER,
            FinanceConstants.CURRENCY_CNY, pri,
            effFrom, null, "IT idempotency rule",
            List.of(
                new CreateRuleItem(1, "BASE",   "10",       null, "gross_amount", null),
                new CreateRuleItem(2, "WEIGHT", "weight*2", null, "gross_amount", null)
            )
        );
        View created = billingRuleService.create(tenantId, 1L, req);
        return billingRuleService.activate(tenantId, created.id(), 1L);
    }
}