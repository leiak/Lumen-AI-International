package com.lumen.dispatch.event;

import com.lumen.extension.outbox.DomainEvent;
import com.lumen.extension.outbox.DomainEventTypeRegistrar;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * lumen-dispatch 模块的 {@link DomainEventTypeRegistrar} 实现 (Iteration 4.1.x fix).
 *
 * <p>把 lumen-dispatch 写入 {@code t_event_outbox} 的两类业务事件注册到
 * {@link com.lumen.extension.outbox.OutboxDispatcher}, 让 dispatcher 能反序列化
 * payload 并重新 publish 到 Spring {@code ApplicationEventPublisher}.
 *
 * <p>修复 4.1.x-PR2 上线后的告警:
 * {@code Unknown event type: delivery_order.state_changed} — OutboxDispatcher
 * 之前的硬编码 switch 只知道 4 个 country events, 新的 4 个 dispatch 事件类型
 * 全部走到 IllegalArgumentException → retry → 3 次后 DEAD_LETTER, 在测试日志
 * 里产生告警噪声 (虽然不阻塞测试, 因为 OutboxDeliveryEventIT 只 assert 行入表,
 * 不 assert 分发成功).
 *
 * <p>事件类型字符串 {@code delivery_order.state_changed} /
 * {@code delivery_exception.state_changed} 与 {@link com.lumen.dispatch.DispatchConstants}
 * 中 {@code EVENT_TYPE_DELIVERY_*} 常量保持一致 (本类硬编码字符串而非引用
 * 常量, 因为 lumen-extension 不能反向依赖 lumen-dispatch).
 *
 * <p>未来 V5 EP-2 / 4.3 接 finance / notification 真实订阅方时,
 * 添加 {@code @EventListener} 监听 {@link DeliveryOrderStateChangedEvent} 即可,
 * 无需再次修改 dispatcher.
 */
@Component
public class DispatchEventTypeRegistrar implements DomainEventTypeRegistrar {

    /** 与 DispatchConstants.EVENT_TYPE_DELIVERY_ORDER_STATE_CHANGED 一致. */
    private static final String EVENT_TYPE_DELIVERY_ORDER_STATE_CHANGED = "delivery_order.state_changed";

    /** 与 DispatchConstants.EVENT_TYPE_DELIVERY_EXCEPTION_STATE_CHANGED 一致. */
    private static final String EVENT_TYPE_DELIVERY_EXCEPTION_STATE_CHANGED = "delivery_exception.state_changed";

    @Override
    public Map<String, Class<? extends DomainEvent>> registeredTypes() {
        return Map.of(
                EVENT_TYPE_DELIVERY_ORDER_STATE_CHANGED, DeliveryOrderStateChangedEvent.class,
                EVENT_TYPE_DELIVERY_EXCEPTION_STATE_CHANGED, DeliveryExceptionStateChangedEvent.class
        );
    }
}