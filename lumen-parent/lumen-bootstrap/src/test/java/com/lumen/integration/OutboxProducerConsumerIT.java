package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.extension.outbox.EventBus;
import com.lumen.extension.outbox.EventOutbox;
import com.lumen.extension.outbox.EventOutboxMapper;
import com.lumen.extension.outbox.OutboxDispatcher;
import com.lumen.extension.outbox.OutboxStatus;
import com.lumen.extension.outbox.events.CountryStateChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Outbox 模式端到端：
 * 1. {@link EventBus#publish} 写入一行 PENDING 的 outbox 行（与调用方在同一事务）
 * 2. {@link OutboxDispatcher#dispatch} 读取该行，反序列化并通过 Spring
 *    ApplicationEventPublisher 重新发布，{@link OutboxTestListener} 收到事件
 * 3. dispatcher 把该行标记为 DONE
 *
 * 因为 {@code DefaultEventBus.publish} 是 {@code @Transactional(propagation = MANDATORY)}，
 * 这里用 {@link TransactionTemplate} 包一层显式事务，事务提交后 selectList 才能看到该行。
 *
 * 监听器独立成顶层 {@link OutboxTestListener}（见该类注释），避免 static nested
 * {@code @Component} 在 @SpringBootTest 上下文里事件未被 @EventListener 投递的边角问题。
 */
@Import(OutboxTestListener.class)
class OutboxProducerConsumerIT extends IntegrationBase {

    @Autowired EventBus eventBus;
    @Autowired EventOutboxMapper outboxMapper;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxTestListener listener;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void publish_writesOutboxRow_andDispatcherDelivers() {
        listener.clear();
        // 多租户拦截器（MybatisPlusConfig.TenantLineInnerInterceptor）要求
        // 查询时 tenant_id 必须等于 TenantContext 的值；这里事件 tenantId=1L，
        // 所以查询前要把 TenantContext 设为 1L，避免被自动注入 `AND tenant_id = 0` 过滤掉。
        TenantContext.set(1L);
        try {
            // 【复用模式 fix】aggregateId 派生自 UUID long，保证每次测试不撞
            //     上一次跑留下的同 aggregateId 行（reuse mode 不清表）。
            //     aggregateId 列存的是 String.valueOf(countryId)，所以 select 用
            //     String.valueOf(countryId) 过滤。
            long countryId = Math.abs(UUID.randomUUID().getLeastSignificantBits());

            // 必须显式开事务：publish() 的传播是 MANDATORY
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(status ->
                    eventBus.publish(new CountryStateChangedEvent(1L, countryId, "DRAFT", "ACTIVE", 1L))
            );

            // 1) outbox 立即有 1 行 PENDING（事务已提交，可见）
            List<EventOutbox> rows = outboxMapper.selectList(
                    new LambdaQueryWrapper<EventOutbox>().eq(EventOutbox::getAggregateId, String.valueOf(countryId))
            );
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING.name());

            // 清掉 publish 路径上同步派发的事件（DefaultEventBus.publish 末尾会
            // publisher.publishEvent 一次同模块消费），下面只校验 dispatcher 路径
            // 重新发布的事件。
            listener.clear();

            // 2) 手动触发一次 dispatcher（绕开 @Scheduled 轮询）。
            // 这里最多重试 5 次，避免与后台 @Scheduled 调度线程在 FOR UPDATE
            // SKIP LOCKED 上的偶发争用导致本测试线程拿到空 batch。
            EventOutbox row = null;
            for (int i = 0; i < 5; i++) {
                dispatcher.dispatch();
                row = outboxMapper.selectById(rows.get(0).getId());
                if (row.getStatus().equals(OutboxStatus.DONE.name())) {
                    break;
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            // 3) 行变 DONE
            assertThat(row).isNotNull();
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.DONE.name());

            // 4) OutboxTestListener 收到了 1 个事件，newState=ACTIVE
            assertThat(listener.getReceived()).hasSize(1);
            assertThat(listener.getReceived().get(0).getNewState()).isEqualTo("ACTIVE");
            assertThat(listener.getReceived().get(0).getCountryId()).isEqualTo(countryId);
            assertThat(listener.getReceived().get(0).getOldState()).isEqualTo("DRAFT");
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }
}