package com.lumen.integration;

import com.lumen.extension.outbox.events.CountryStateChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同步 @EventListener：{@link com.lumen.extension.outbox.OutboxDispatcher} 通过
 * {@code ApplicationEventPublisher} 同步发布事件，Spring 默认事件是同步派发，
 * 所以 dispatcher 线程会直接调用 {@link #on(CountryStateChangedEvent)}。
 *
 * <p>仅做计数 —— 用于 {@code OutboxConcurrencyIT} 验证
 * {@code FOR UPDATE SKIP LOCKED} 的语义：两个并发 dispatcher 线程总共只会
 * 让每个 outbox 行被处理一次（不会因为竞争而出现一条行被两个 dispatcher
 * 同时处理的情况）。
 *
 * <p>作为独立的顶层类（而非 {@code OutboxConcurrencyIT} 的 static nested
 * {@code @Component}）注册的考量同 {@link OutboxTestListener}：避免
 * {@code EventListenerMethodProcessor} 在 @SpringBootTest 上下文里漏掉
 * 嵌套类的 @EventListener 方法。
 */
@Component
public class OutboxCountingListener {

    private final AtomicInteger count = new AtomicInteger(0);

    public void clear() {
        count.set(0);
    }

    public AtomicInteger getCount() {
        return count;
    }

    @EventListener
    public void on(CountryStateChangedEvent e) {
        count.incrementAndGet();
    }
}