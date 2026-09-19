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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@code FOR UPDATE SKIP LOCKED} 的并发语义：
 * 两个 {@link OutboxDispatcher#dispatch()} 线程同时拉取 PENDING 行时，
 * 每一行只能被其中一个线程抢到行锁并处理 —— 不会出现"两 dispatcher 都拿到了
 * 同一行并各自调用一次 {@code publisher.publishEvent}"的双投递。
 *
 * <p>测试方法：
 * <ol>
 *   <li>通过 {@link EventBus#publish} 写入 N 行 outbox（事务提交后可见）。</li>
 *   <li>把 {@code next_retry_at} 拨到过去，让 dispatcher 可以立刻 claim。</li>
 *   <li>两个线程并发执行 {@code dispatcher.dispatch()}。</li>
 *   <li>断言：N 行全部 DONE，且 {@link OutboxCountingListener} 计数恰好等于 N
 *       （没有重复处理）。</li>
 * </ol>
 *
 * <p>注意点：
 * <ul>
 *   <li>{@code EventBus.publish} 是 {@code @Transactional(MANDATORY)}，必须用
 *       {@link TransactionTemplate} 包一层显式事务。</li>
 *   <li>{@code TenantLineInnerInterceptor} 会按 {@code TenantContext} 过滤，
 *       所以查询前需要 {@code TenantContext.set(1L)}。</li>
 *   <li>{@code publish} 路径末端的同步派发也会触发监听器，需要在 dispatcher
 *       调用前清零计数。</li>
 *   <li>{@link OutboxCountingListener} 走顶层 {@code @Component} + {@code @Import}
 *       模式（与 {@link OutboxTestListener} 一致），避开 static nested
 *       {@code @Component} 在 @SpringBootTest 里 @EventListener 偶发漏注册的边角问题。</li>
 * </ul>
 */
@Import(OutboxCountingListener.class)
class OutboxConcurrencyIT extends IntegrationBase {

    @Autowired EventBus eventBus;
    @Autowired EventOutboxMapper outboxMapper;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxCountingListener listener;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void twoDispatchersConcurrently_eachRowProcessedOnce() throws Exception {
        listener.clear();
        TenantContext.set(1L);
        try {
            int N = 10;
            long aggregateIdStart = 300L;

            // 1) 写 N 行 outbox
            TransactionTemplate tx = new TransactionTemplate(txManager);
            for (int i = 0; i < N; i++) {
                long countryId = aggregateIdStart + i;
                tx.executeWithoutResult(status ->
                        eventBus.publish(new CountryStateChangedEvent(1L, countryId, "DRAFT", "ACTIVE", 1L))
                );
            }

            // 清掉 publish 路径上同步派发的事件（DefaultEventBus.publish 末尾会
            // publisher.publishEvent 一次同模块消费），下面只关心 dispatcher 路径
            // 重新发布的事件。
            listener.clear();

            // 2) 把这些行的 next_retry_at 拨到过去，让 dispatcher 立刻可以 claim。
            //    范围按 aggregateId 字符串 300~309 过滤（与事件 aggregateId() 一致）。
            List<EventOutbox> rows = outboxMapper.selectList(
                    new LambdaQueryWrapper<EventOutbox>()
                            .ge(EventOutbox::getAggregateId, "300")
                            .le(EventOutbox::getAggregateId, "309")
            );
            assertThat(rows).hasSize(N);
            LocalDateTime past = LocalDateTime.now().minusSeconds(1);
            for (EventOutbox r : rows) {
                r.setNextRetryAt(past);
                outboxMapper.updateById(r);
            }

            // 3) 两个 dispatcher 线程并发 dispatch。
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);
            try {
                for (int i = 0; i < 2; i++) {
                    pool.submit(() -> {
                        try {
                            // 轮询直到目标行全部 DONE，避免一次 batch 拿不全。
                            for (int attempt = 0; attempt < 20; attempt++) {
                                dispatcher.dispatch();
                                long doneCount = outboxMapper.selectCount(
                                        new LambdaQueryWrapper<EventOutbox>()
                                                .ge(EventOutbox::getAggregateId, "300")
                                                .le(EventOutbox::getAggregateId, "309")
                                                .eq(EventOutbox::getStatus, OutboxStatus.DONE.name())
                                );
                                if (doneCount >= N) {
                                    break;
                                }
                                try { Thread.sleep(50); } catch (InterruptedException ignored) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                assertThat(latch.await(60, TimeUnit.SECONDS))
                        .as("two dispatchers should complete within 60s")
                        .isTrue();
            } finally {
                pool.shutdownNow();
            }

            // 4) N 行全部 DONE
            List<EventOutbox> done = outboxMapper.selectList(
                    new LambdaQueryWrapper<EventOutbox>()
                            .ge(EventOutbox::getAggregateId, "300")
                            .le(EventOutbox::getAggregateId, "309")
                            .eq(EventOutbox::getStatus, OutboxStatus.DONE.name())
            );
            assertThat(done).as("all N rows should be DONE after concurrent dispatch")
                    .hasSize(N);

            // 5) 监听器收到的事件总数恰好等于 N —— SKIP LOCKED 保证了
            //    "每行只被其中一个 dispatcher 线程拿到锁并重新发布一次"。
            assertThat(listener.getCount().get())
                    .as("each row should be delivered exactly once across 2 concurrent dispatchers")
                    .isEqualTo(N);
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }
}