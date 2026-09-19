package com.lumen.integration;

import com.lumen.extension.outbox.events.CountryStateChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 故意抛异常的 {@code @EventListener}，专供 {@code OutboxRetryIT} 用，
 * 用来驱动 outbox dispatcher 走"失败 → 重试 → 入死信"分支。
 *
 * <p>作为独立的顶层类（而非 IT 类的 static nested {@code @Component}）注册的
 * 考量：与 {@link OutboxTestListener} 完全相同，避免 {@code @Import(Outer.Nested.class)}
 * 在 @SpringBootTest 上下文里 {@code EventListenerMethodProcessor}
 * 未扫描到嵌套类上的 {@code @EventListener} 方法的边角问题。
 *
 * <p>{@link #shouldFail} 控制是否抛异常；测试通过 {@code @Autowired} 拿到这个 bean 后
 * 用 setter 切换"正常 / 失败"模式，并读取 {@link #callCount} 验证调用次数。
 */
@Component
public class OutboxRetryFailingListener {

    /**
     * 默认 false：{@code OutboxRetryFailingListener} 是 {@code @Component}，
     * 会被所有 {@code @SpringBootTest} 共享上下文里的 IT 类（包括
     * {@link OutboxProducerConsumerIT}）自动投递事件，所以默认值必须是无害的（不抛）。
     * {@code OutboxRetryIT} 在测试逻辑里打开失败模式，并在 finally 里关回 false。
     */
    private final AtomicBoolean shouldFail = new AtomicBoolean(false);
    private final AtomicInteger callCount = new AtomicInteger(0);

    public void setShouldFail(boolean v) {
        shouldFail.set(v);
    }

    public boolean getShouldFail() {
        return shouldFail.get();
    }

    public int getCallCount() {
        return callCount.get();
    }

    public void setCallCount(int v) {
        callCount.set(v);
    }

    @EventListener
    public void on(CountryStateChangedEvent e) {
        callCount.incrementAndGet();
        if (shouldFail.get()) {
            throw new IllegalStateException("simulated listener failure for retry test");
        }
    }
}