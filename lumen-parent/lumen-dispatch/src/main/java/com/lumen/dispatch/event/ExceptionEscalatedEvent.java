package com.lumen.dispatch.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lumen.extension.outbox.DomainEvent;
import lombok.Getter;

import java.util.Objects;

/**
 * 异常工单 SLA 升级事件 (Iteration 4.2 PR5).
 *
 * <p>由 {@link com.lumen.cs.service.EscalationService#scan} 在检测到工单
 * 业务分钟数超 {@code t_sla_config.resolve_minutes} 时通过
 * {@link com.lumen.extension.outbox.EventBus#publish} 写入
 * {@code t_event_outbox}.
 *
 * <p>lumen-dispatch 在 PR5 引入 {@code ExceptionEscalatedEventListener}
 * 订阅该事件, 更新 {@code wms_delivery_exception.handler} 为
 * {@link #getNewHandlerUsername} (升到 L2 / L3 后的目标处理人).
 *
 * <p><b>为什么事件放在 lumen-dispatch.event (而非 lumen-cs.event):</b>
 * <ul>
 *   <li>lumen-cs 不依赖 lumen-dispatch (spec §2 模块边界).</li>
 *   <li>lumen-cs 需要把此事件 publish 到 outbox, 而 lumen-dispatch 需要 listen
 *       它 —— 双向引用最简洁方式是把事件类放在 {@code lumen-dispatch.event}
 *       并让 lumen-cs 加 lumen-dispatch 的 Maven 依赖 (仅用于引用事件 POJO).</li>
 *   <li>其他 4.1.x 事件 ({@link DeliveryOrderStateChangedEvent},
 *       {@link DeliveryExceptionStateChangedEvent}) 也都在
 *       {@code lumen-dispatch.event}, 保持一致性.</li>
 * </ul>
 *
 * <p>payload 字段:
 * <ul>
 *   <li>{@code exceptionId}, {@code tenantId} — 异常工单标识</li>
 *   <li>{@code fromLevel} → {@code toLevel} — 升级前后的 level</li>
 *   <li>{@code severity} — HIGH / MEDIUM / LOW (由 type 派生)</li>
 *   <li>{@code businessMinutesElapsed} — 已耗费业务分钟数 (决策依据)</li>
 *   <li>{@code newHandlerUsername} — 升级后目标处理人</li>
 *   <li>{@code occurredAt} — 事件发生时间 (由 {@link DomainEvent} 基类生成)</li>
 * </ul>
 */
@Getter
public class ExceptionEscalatedEvent extends DomainEvent {

    /** 与 {@link #eventType()} 一致, 给 {@link com.lumen.extension.outbox.DomainEventTypeRegistrar} 注册用. */
    public static final String EVENT_TYPE = "exception.escalated";

    /** aggregate type 与 {@code wms_delivery_exception} 表的语义聚合根对齐. */
    public static final String AGGREGATE_TYPE = "WmsDeliveryException";

    private final Long tenantId;
    private final Long exceptionId;
    private final String fromLevel;
    private final String toLevel;
    private final String severity;
    private final Long businessMinutesElapsed;
    private final String newHandlerUsername;

    @JsonCreator
    public ExceptionEscalatedEvent(
            @JsonProperty("tenantId") Long tenantId,
            @JsonProperty("exceptionId") Long exceptionId,
            @JsonProperty("fromLevel") String fromLevel,
            @JsonProperty("toLevel") String toLevel,
            @JsonProperty("severity") String severity,
            @JsonProperty("businessMinutesElapsed") Long businessMinutesElapsed,
            @JsonProperty("newHandlerUsername") String newHandlerUsername) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(exceptionId, "exceptionId");
        Objects.requireNonNull(toLevel, "toLevel");
        this.tenantId = tenantId;
        this.exceptionId = exceptionId;
        this.fromLevel = fromLevel;
        this.toLevel = toLevel;
        this.severity = severity;
        this.businessMinutesElapsed = businessMinutesElapsed;
        this.newHandlerUsername = newHandlerUsername;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    @Override
    public String aggregateId() {
        return String.valueOf(exceptionId);
    }

    @Override
    public Long tenantId() {
        return tenantId;
    }
}
