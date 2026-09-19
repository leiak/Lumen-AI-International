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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Outbox 重试 → 死信 链路：
 * <ol>
 *   <li>监听器每次都抛异常，{@link OutboxDispatcher#dispatch} 把行从 PENDING
 *       改成 PENDING（retryCount++，nextRetryAt 推后）。</li>
 *   <li>连续 3 次失败后（maxRetries=3 的语义是 TOTAL attempts = 3），
 *       状态翻转为 {@link OutboxStatus#DEAD_LETTER}。</li>
 * </ol>
 *
 * <p>监听器独立成顶层 {@link OutboxRetryFailingListener}（见该类注释），
 * 避免 static nested {@code @Component} 在 @SpringBootTest 上下文里事件未被
 * @EventListener 投递的边角问题（同 Task 7 在 commit fdd65d0 的修复）。
 */
@Import(OutboxRetryFailingListener.class)
class OutboxRetryIT extends IntegrationBase {

    @Autowired EventBus eventBus;
    @Autowired EventOutboxMapper outboxMapper;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxRetryFailingListener failingListener;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void listenerAlwaysFails_rowGoesDeadLetterAfterThreeAttempts() {
        // 多租户拦截器要求查询时 tenant_id 等于 TenantContext，这里事件 tenantId=1L
        TenantContext.set(1L);
        try {
            // 1) 默认让监听器"不抛"，以便 publish 路径上的同步派发（DefaultEventBus.publish
            //    末尾会 publisher.publishEvent）不会把事务 rollback 掉 —— 如果这里
            //    直接 setShouldFail(true)，事务在 insert outbox 行之后会被 listener 异常
            //    打断，selectList 就查不到任何行。
            failingListener.setShouldFail(false);
            failingListener.setCallCount(0);

            // 2) 显式开事务：publish() 传播是 MANDATORY
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(status ->
                    eventBus.publish(new CountryStateChangedEvent(1L, 200L, "DRAFT", "ACTIVE", 1L))
            );

            // 3) 进入重试阶段：清掉 publish 路径同步派发留下的副作用，再打开失败模式
            failingListener.setCallCount(0);
            failingListener.setShouldFail(true);

            // 4) 找到刚才插入的那一行
            List<EventOutbox> rows = outboxMapper.selectList(
                    new LambdaQueryWrapper<EventOutbox>().eq(EventOutbox::getAggregateId, "200")
            );
            assertThat(rows).hasSize(1);
            Long rowId = rows.get(0).getId();

            // 5) 循环派发：每次失败后把 nextRetryAt 拉回到过去，让下次 lockPendingBatch
            //    能立即拿到（默认 30s/120s/600s 后才重试，测试里等不起）。
            //    最多循环 4 次：1 → retryCount=1 (still PENDING)；
            //                    2 → retryCount=2 (still PENDING)；
            //                    3 → retryCount=3 == maxRetries → DEAD_LETTER；
            //                    第 4 次循环时检测到 DEAD_LETTER 直接 break。
            for (int i = 0; i < 4; i++) {
                EventOutbox row = outboxMapper.selectById(rowId);
                if (OutboxStatus.DEAD_LETTER.name().equals(row.getStatus())) {
                    break;
                }
                row.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
                outboxMapper.updateById(row);

                dispatcher.dispatch();
            }

            // 6) 最终断言
            EventOutbox finalRow = outboxMapper.selectById(rowId);
            assertThat(finalRow.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER.name());
            assertThat(finalRow.getRetryCount()).isEqualTo(3);
            assertThat(failingListener.getCallCount()).isGreaterThanOrEqualTo(3);
        } finally {
            // 关掉失败模式，避免污染后续共享 @SpringBootTest 上下文的 IT
            // （其它 IT 也会被这个 @Component 投递事件）
            failingListener.setShouldFail(false);
            TenantContext.clear();
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }
}