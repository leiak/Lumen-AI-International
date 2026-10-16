package com.lumen.dispatch.listener;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.event.ExceptionEscalatedEvent;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异常工单 SLA 升级事件监听器 (Iteration 4.2 PR5).
 *
 * <p>订阅 {@link ExceptionEscalatedEvent} (lumen-cs EscalationService 发出的 outbox 事件),
 * 把 {@code wms_delivery_exception.handler} 字段更新为 {@code newHandlerUsername}
 * (L2/L3 升级后的目标处理人).
 *
 * <p>异步执行: 走 {@code outboxExecutor} (lumen-extension 配置的线程池), 避免阻塞
 * 业务事务 (outbox dispatcher 把事件重新 publish 到 Spring
 * {@code ApplicationEventPublisher}, 这里 @EventListener 收到后异步处理).
 *
 * <p><b>TenantContext 处理:</b> 异步 listener 在 {@code outboxExecutor} 线程上
 * 执行, ThreadLocal 不会继承. 必须从事件 {@link ExceptionEscalatedEvent#getTenantId()}
 * 取值并 set 到 TenantContext, 否则 {@code MybatisPlusConfig} 的
 * {@code TenantLineInnerInterceptor} 会以 tid=0 过滤, 看不到非 tenant=0 行.
 * 见 {@link com.lumen.dispatch.event.WmsOutboundShippedListener} 同款模式.
 *
 * <p>幂等: 即使同一事件被 dispatch 多次 (UNIQUE KEY 已在 lumen-cs 防并发, 但
 * outbox dispatcher 重试或双发仍可能发生), handler 字段多次写入相同值
 * (L2/L3 handler 是确定性的, cs_supervisor_42 / cs_manager_1), 数据库层面
 * MyBatis-Plus @Version 乐观锁会检测到冲突并抛 {@code OptimisticLockException} —
 * 监听器不 catch, 让 outbox retry 链路把这条事件标记为失败重试 (PR6+ 引入
 * idempotent handler set 字段后, 可改用 {@code updateById} 强制写).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExceptionEscalatedEventListener {

    private final WmsDeliveryExceptionMapper mapper;

    @EventListener
    @Async("outboxExecutor")
    public void onEscalated(ExceptionEscalatedEvent e) {
        try {
            TenantContext.set(e.getTenantId());
            WmsDeliveryException ex = mapper.selectById(e.getExceptionId());
            if (ex == null) {
                log.warn("Escalation event for unknown exception {} (tenant={}), ignored",
                    e.getExceptionId(), e.getTenantId());
                return;
            }
            ex.setHandler(e.getNewHandlerUsername());
            mapper.updateById(ex);
            log.info("Exception {} escalated {} -> {}, handler set to {} (tenant={}, elapsed={}min)",
                e.getExceptionId(), e.getFromLevel(), e.getToLevel(),
                e.getNewHandlerUsername(), e.getTenantId(), e.getBusinessMinutesElapsed());
        } catch (Exception ex) {
            // listener 永远不抛 — 失败仅记日志, 由 outbox 兜底重试
            log.error("Failed to update handler for escalated exception {} (tenant={})",
                e.getExceptionId(), e.getTenantId(), ex);
        } finally {
            TenantContext.clear();
        }
    }
}

