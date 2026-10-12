package com.lumen.dispatch.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.extension.outbox.DomainEvent;
import lombok.Getter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;

/**
 * 异常工单 (WmsDeliveryException) 状态机迁移事件 (Iteration 4.1.x PR2).
 *
 * <p>由 {@link com.lumen.dispatch.service.impl.DeliveryExceptionServiceImpl} 在
 * 4 个状态机迁移 (investigate / resolve / close / reopen) 之后通过
 * {@link com.lumen.extension.outbox.EventBus#publish} 写入
 * {@code t_event_outbox}.
 *
 * <p>{@code create} 不是状态机迁移, 因此 publish 不会在 {@code create()} 上调用
 * —— 工单的初始状态是 {@code OPEN}, 不存在 {@code fromState}.
 *
 * <p>payload 字段:
 * <ul>
 *   <li>{@code exceptionId}, {@code orderId}, {@code type} — 异常工单标识</li>
 *   <li>{@code tenantId} — 多租户隔离键</li>
 *   <li>{@code fromState}, {@code toState}, {@code action} — 状态机迁移三元组</li>
 *   <li>{@code handler}, {@code description} — 工单处理人 / 描述</li>
 *   <li>{@code actor} — 当前操作人 (Spring Security {@code Authentication.getName()};
 *       未登录则 {@code "system"})</li>
 *   <li>{@code occurredAt} — 事件发生时间 (由 {@link DomainEvent} 基类生成)</li>
 * </ul>
 */
@Getter
public class DeliveryExceptionStateChangedEvent extends DomainEvent {

    private final Long tenantId;
    private final Long exceptionId;
    private final Long orderId;
    private final String type;
    private final String fromState;
    private final String toState;
    private final String action;
    private final String handler;
    private final String description;
    private final String actor;

    @JsonCreator
    public DeliveryExceptionStateChangedEvent(
            @JsonProperty("tenantId") Long tenantId,
            @JsonProperty("exceptionId") Long exceptionId,
            @JsonProperty("orderId") Long orderId,
            @JsonProperty("type") String type,
            @JsonProperty("fromState") String fromState,
            @JsonProperty("toState") String toState,
            @JsonProperty("action") String action,
            @JsonProperty("handler") String handler,
            @JsonProperty("description") String description,
            @JsonProperty("actor") String actor) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(exceptionId, "exceptionId");
        Objects.requireNonNull(toState, "toState");
        this.tenantId = tenantId;
        this.exceptionId = exceptionId;
        this.orderId = orderId;
        this.type = type;
        this.fromState = fromState;
        this.toState = toState;
        this.action = action;
        this.handler = handler;
        this.description = description;
        this.actor = actor;
    }

    @Override
    public String eventType() {
        return DispatchConstants.EVENT_TYPE_DELIVERY_EXCEPTION_STATE_CHANGED;
    }

    @Override
    public String aggregateType() {
        return DispatchConstants.AGGREGATE_TYPE_DELIVERY_EXCEPTION;
    }

    @Override
    public String aggregateId() {
        return String.valueOf(exceptionId);
    }

    @Override
    public Long tenantId() {
        return tenantId;
    }

    // ================================================================
    // Factory
    // ================================================================

    /**
     * 从异常工单实体构建迁移事件.
     *
     * <p>{@code actor} 自动从 Spring Security 取当前登录用户名, 未登录降级为
     * {@code "system"} (与 {@link DeliveryOrderStateChangedEvent} 同款).
     */
    public static DeliveryExceptionStateChangedEvent of(WmsDeliveryException ex, String from, String to, String action) {
        return new DeliveryExceptionStateChangedEvent(
                ex.getTenantId(),
                ex.getId(),
                ex.getOrderId(),
                ex.getType(),
                from,
                to,
                action,
                ex.getHandler(),
                ex.getDescription(),
                resolveActor());
    }

    private static String resolveActor() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(auth.getPrincipal())) {
                return auth.getName();
            }
        } catch (Exception ignored) {
        }
        return "system";
    }
}