package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.extension.outbox.EventBus;
import com.lumen.extension.outbox.EventOutbox;
import com.lumen.extension.outbox.EventOutboxMapper;
import com.lumen.extension.outbox.OutboxDispatcher;
import com.lumen.extension.outbox.events.CountryStateChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Outbox + 异步消费 端到端：
 *
 * <ol>
 *   <li>{@link #publishInsideRolledBackTransaction_noOutboxRowAndNotDelivered} —
 *       {@link EventBus#publish} 在事务里插入一行 outbox，事务被回滚 →
 *       该行消失，dispatcher 路径上没有 row 可消费，{@link OutboxAsyncListener}
 *       不应该因 dispatcher 路径而收到任何事件。</li>
 *   <li>{@link #asyncListenerReceivesAfterCommit_viaOutboxDispatcher} —
 *       事务提交一行 outbox → {@link OutboxDispatcher#dispatch}（或后台
 *       {@code @Scheduled} 轮询）拾取该行并通过 {@code ApplicationEventPublisher}
 *       重新发布事件，{@link OutboxAsyncListener}（@Async on outboxExecutor）
 *       异步收到事件。</li>
 * </ol>
 *
 * <p>注意点：
 * <ul>
 *   <li>{@link com.lumen.extension.outbox.DefaultEventBus#publish} 末尾会
 *       同步调用一次 {@code ApplicationEventPublisher.publishEvent}（同模块消费
 *       入口），这同样会通过 async 代理提交到 {@code outboxExecutor} 并触发
 *       本测试里的 @Async @EventListener —— 同步派发的 async 任务通常会在
 *       几十毫秒内完成；测试通过 sleep 等待 + clear() 把"sync-publish 副作用"
 *       清掉，只断言"之后 dispatcher 路径上又新增了事件"。</li>
 *   <li>{@code EventBus.publish} 是 {@code @Transactional(MANDATORY)}，必须用
 *       {@link TransactionTemplate} 包一层显式事务。</li>
 *   <li>本测试无法严格断言 received.size() == 1：{@code OutboxDispatcher} 是
 *       {@code @Scheduled(fixedDelayString = "2000")} 也会并发轮询，理论上
 *       可能与本测试手动 {@code dispatcher.dispatch()} 各投递一次。测试断言
 *       "至少 1 条来自 dispatcher 路径的事件 + 事件 countryId 匹配" 即可。</li>
 *   <li>{@link OutboxAsyncListener} 走顶层 {@code @Component} + {@code @Import}
 *       模式（与 {@link OutboxTestListener} 一致），避开 static nested
 *       {@code @Component} 在 @SpringBootTest 里 @EventListener 偶发漏注册的
 *       边角问题。</li>
 * </ul>
 *
 * <p>{@code TransactionTemplate} 不是 Spring 默认 bean，必须在测试里现造：
 * {@code new TransactionTemplate(txManager)}。
 *
 * <p>{@code @Async("outboxExecutor")} 需要 {@code @EnableAsync} 处于激活状态。
 * 这里通过 {@code @SpringBootTest} 加载 {@code LumenApplication}（已标注
 * {@code @EnableAsync}），同时 {@code AsyncConfig} 也自带
 * {@code @EnableAsync}，所以异步能力始终可用。
 */
@Import(OutboxAsyncListener.class)
class OutboxAsyncIT extends IntegrationBase {

    @Autowired EventBus eventBus;
    @Autowired EventOutboxMapper outboxMapper;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxAsyncListener listener;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void publishInsideRolledBackTransaction_noOutboxRowAndNotDelivered() throws Exception {
        listener.clear();
        // 多租户拦截器要求查询时 tenant_id 必须等于 TenantContext；这里事件 tenantId=1L。
        TenantContext.set(1L);
        try {
            // 必须显式开事务：publish() 的传播是 MANDATORY。
            //
            // 回滚语义(4.5D-1 收尾时订正 —— 见 README.dev.md §15.18):
            // 最外层 status.setRollbackOnly() 在 commit 时是**静默回滚、零异常** ——
            // Spring 6.1.6 AbstractPlatformTransactionManager.commit() 里
            // defStatus.isLocalRollbackOnly() 分支直接 processRollback(status, false) 然后
            // return —— **不经过 processCommit()**, 更不抛异常。
            // UnexpectedRollbackException **只在全局 rollback-only 时才抛**
            // (commit() 的 isGlobalRollbackOnly() 分支 → processRollback(status, true);
            //  内层参与型事务失败并被标 rollback-only 的典型场景) —— 最外层这个
            // setRollbackOnly() 不会抛它。
            //
            // 所以这里**不需要** try/catch。而且原来那个 catch (RuntimeException commitEx)
            // 是有害的: ① 它永不命中(上面那条); ② 它宽到能吞掉 eventBus.publish() 的
            // **真实失败** —— 「压根没写进去」也会让下面的 isEmpty() 空洞地通过。
            //
            // 改成: 事务回调内先断言 outbox 行**确实已经写入**(未提交, 但对本事务自己的
            // 连接可见), 回滚**之后**才断言查不到行 —— 少了事务内那一步,
            // 「压根没写进去」这条路径就会让断言空洞地绿。
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(status -> {
                eventBus.publish(new CountryStateChangedEvent(1L, 400L, "DRAFT", "ACTIVE", 1L));

                // 事务内: 证明这一行**确实写进去了**(未提交, 但对本事务自己的连接可见)。
                assertThat(outboxMapper.selectList(
                        new LambdaQueryWrapper<EventOutbox>().eq(EventOutbox::getAggregateId, "400")))
                    .as("事务内: outbox 行已经写入 (未提交) —— 证明下面断言的「回滚后为空」是真回滚")
                    .hasSize(1);

                status.setRollbackOnly();
            });

            // 1) 回滚后没有 outbox 行
            List<EventOutbox> rows = outboxMapper.selectList(
                    new LambdaQueryWrapper<EventOutbox>().eq(EventOutbox::getAggregateId, "400")
            );
            assertThat(rows)
                    .as("outbox row must not exist after rollback")
                    .isEmpty();

            // 2) 等 DefaultEventBus.publish() 末尾同步 publishEvent 触发的
            //    async 副作用跑完（最多几十毫秒），然后 clear。
            //    然后手动触发 dispatcher —— dispatcher 路径上没有 row，不会投递。
            Thread.sleep(500);
            listener.clear();

            dispatcher.dispatch();
            Thread.sleep(300);

            // 3) dispatcher 路径上没有 row，所以 listener 不应该再收到
            //    来自 dispatcher 路径的任何事件（sync-publish 副作用已被 clear
            //    抹掉）。
            assertThat(listener.getReceived())
                    .as("dispatcher path must not deliver any event when the outbox row was rolled back")
                    .isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void asyncListenerReceivesAfterCommit_viaOutboxDispatcher() throws Exception {
        listener.clear();
        TenantContext.set(1L);
        try {
            // 1) 显式开事务提交一行 outbox
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(status ->
                    eventBus.publish(new CountryStateChangedEvent(1L, 401L, "DRAFT", "ACTIVE", 1L))
            );

            // 2) 复用模式下背景 @Scheduled poller 可能在我们 dispatch 之前就把
            //    行处理了 —— listener 已经收到过；不强制 clear，让 dispatcher
            //    路径的事件叠加进来。最后按 countryId 过滤断言。
            Thread.sleep(500);

            // 3) 手动触发 dispatcher（绕开 @Scheduled 的 2s 轮询）；循环重试
            //    避免与 FOR UPDATE SKIP LOCKED 偶发争用。
            for (int i = 0; i < 10; i++) {
                dispatcher.dispatch();
                long mine = listener.getReceived().stream()
                        .filter(e -> e.getCountryId().equals(401L)).count();
                if (mine > 0) {
                    break;
                }
                Thread.sleep(200);
            }

            // 4) 至少收到 1 条来自 dispatcher 路径的事件，且 countryId 匹配。
            //    （不严格 == 1 的原因见类注释 —— @Scheduled 轮询与手动 dispatch
            //    可能各投递一次。）
            assertThat(listener.getReceived())
                    .as("async listener must receive at least 1 event from dispatcher path")
                    .isNotEmpty();
            assertThat(listener.getReceived())
                    .as("all delivered events must carry the dispatched aggregateId=401")
                    .allSatisfy(e -> assertThat(e.getCountryId()).isEqualTo(401L));
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }
}