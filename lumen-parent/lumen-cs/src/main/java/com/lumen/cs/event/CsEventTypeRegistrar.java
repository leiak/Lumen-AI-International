package com.lumen.cs.event;

import com.lumen.dispatch.event.ExceptionEscalatedEvent;
import com.lumen.extension.outbox.DomainEvent;
import com.lumen.extension.outbox.DomainEventTypeRegistrar;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * lumen-cs 模块的 {@link DomainEventTypeRegistrar} 实现 (Iteration 4.2 PR5).
 *
 * <p>把 lumen-cs 写入 {@code t_event_outbox} 的业务事件
 * ({@code exception.escalated}) 注册到
 * {@link com.lumen.extension.outbox.OutboxDispatcher}, 让 dispatcher 能反序列化
 * payload 并重新 publish 到 Spring {@code ApplicationEventPublisher},
 * 跨模块 (lumen-dispatch 的 {@code ExceptionEscalatedEventListener}) 订阅.
 *
 * <p>事件类型字符串与 {@link ExceptionEscalatedEvent#eventType()} 保持一致
 * (本类硬编码字符串而非引用常量, 因为 lumen-extension 不能反向依赖 lumen-cs,
 * lumen-cs 也不能反向依赖 lumen-extension 的反射).
 *
 * <p>未来 4.3 接 finance / notification 等更多订阅方时, 只需添加 {@code @EventListener}
 * 监听 {@link ExceptionEscalatedEvent} 即可, 无需再次修改 dispatcher.
 */
@Component
public class CsEventTypeRegistrar implements DomainEventTypeRegistrar {

    /** 与 {@link ExceptionEscalatedEvent#eventType()} 一致. */
    private static final String EVENT_TYPE_EXCEPTION_ESCALATED = "exception.escalated";

    @Override
    public Map<String, Class<? extends DomainEvent>> registeredTypes() {
        return Map.of(
                EVENT_TYPE_EXCEPTION_ESCALATED, ExceptionEscalatedEvent.class
        );
    }
}
