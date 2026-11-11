package com.lumen.order.event;

import com.lumen.extension.outbox.DomainEvent;
import com.lumen.extension.outbox.DomainEventTypeRegistrar;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * lumen-order 模块的 {@link DomainEventTypeRegistrar} 实现 (Iteration 4.6).
 *
 * <p>把 lumen-order 写入 {@code t_event_outbox} 的业务事件注册到
 * {@link com.lumen.extension.outbox.OutboxDispatcher}, 让 dispatcher 能反序列化
 * payload 并重新 publish 到 Spring {@code ApplicationEventPublisher}.
 *
 * <p><b>不加会怎样</b>: {@code OutboxDispatcher.parseEvent} 对未注册的 event_type 抛
 * {@code IllegalArgumentException("Unknown event type: ...")} → 该 outbox 行被当毒丸,
 * 每次失败 {@code retryCount + 1}, {@code retryCount >= maxRetries} 时转
 * {@code DEAD_LETTER}, 事件永久丢失。既有 {@code DispatchEventTypeRegistrar} /
 * {@code CsEventTypeRegistrar} 都是为同一个坑加的。
 *
 * <p>事件类型字符串与 {@link OrderStateChangedEvent#eventType()} 保持一致
 * (本类硬编码字符串而非引用常量, 与既有两个 registrar 同款写法 —— 它们不能反向依赖
 * 事件类所在模块的常量)。
 */
@Component
public class OrderEventTypeRegistrar implements DomainEventTypeRegistrar {

    /** 与 {@link OrderStateChangedEvent#eventType()} 一致. */
    private static final String EVENT_TYPE_ORDER_STATE_CHANGED = "order.state.changed";

    @Override
    public Map<String, Class<? extends DomainEvent>> registeredTypes() {
        return Map.of(
                EVENT_TYPE_ORDER_STATE_CHANGED, OrderStateChangedEvent.class
        );
    }
}
