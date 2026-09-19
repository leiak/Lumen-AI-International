package com.lumen.integration;

import com.lumen.extension.outbox.events.CountryStateChangedEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * 异步 @EventListener：监听事件时把"消费动作"显式提交到
 * {@code outboxExecutor} 线程池上异步执行。
 *
 * <p>为什么不用 {@code @Async("outboxExecutor") @EventListener} 组合？
 * Spring 6 支持两者叠加，但 @EventListener 上的 @Async 需要走 AOP 代理，
 * @SpringBootTest + {@code @Import} 注册的 bean 在某些边角情形下未必
 * 走完整代理链（与 Tasks 7-9 中"static nested @Component 偶发不注册"
 * 是同类问题）。这里改成显式 {@code executor.submit(...)}，语义更直白，
 * 也跟 Task 6 的 {@code AsyncConfig} 里定义的 executor 直接对齐。
 *
 * <p>作为独立的顶层类（而非 IT 类的 static nested {@code @Component}）注册，
 * 避开 nested @Component + @Import 的偶发不识别问题（与
 * {@link OutboxTestListener} / {@link OutboxCountingListener} 同源考量）。
 */
@Component
public class OutboxAsyncListener {

    private final Executor executor;
    private final List<CountryStateChangedEvent> received = new CopyOnWriteArrayList<>();

    public OutboxAsyncListener(@Qualifier("outboxExecutor") Executor executor) {
        this.executor = executor;
    }

    public void clear() {
        received.clear();
    }

    public List<CountryStateChangedEvent> getReceived() {
        return received;
    }

    @EventListener
    public void on(CountryStateChangedEvent e) {
        executor.execute(() -> received.add(e));
    }
}
