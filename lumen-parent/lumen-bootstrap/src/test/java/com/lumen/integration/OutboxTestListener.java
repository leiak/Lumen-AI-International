package com.lumen.integration;

import com.lumen.extension.outbox.events.CountryStateChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 同步 @EventListener：{@link com.lumen.extension.outbox.OutboxDispatcher} 通过
 * {@code ApplicationEventPublisher} 同步发布事件，Spring 默认事件是同步派发，
 * 所以 dispatcher 线程会直接调用 {@link #on(CountryStateChangedEvent)}。
 *
 * <p>作为独立的顶层类（而非 {@code OutboxProducerConsumerIT} 的 static nested
 * {@code @Component}）注册的考量：
 * <ul>
 *   <li>避免 {@code @Import(Outer.Nested.class)} 与 @SpringBootTest 上下文
 *       中组件扫描路径下的隐式注册产生歧义。</li>
 *   <li>让 Spring 的 {@code EventListenerMethodProcessor}（一个
 *       {@code SmartInitializingSingleton}）在 afterSingletonsInstantiated
 *       阶段扫描到这个 bean 的 @EventListener 方法，避免与
 *       {@code OutboxProducerConsumerIT.TestListener} 这种 static nested
 *       @Component 在某些测试场景下出现事件未被投递的问题。</li>
 * </ul>
 */
@Component
public class OutboxTestListener {

    private final List<CountryStateChangedEvent> received = new CopyOnWriteArrayList<>();

    public void clear() {
        received.clear();
    }

    public List<CountryStateChangedEvent> getReceived() {
        return received;
    }

    @EventListener
    public void on(CountryStateChangedEvent e) {
        received.add(e);
    }
}