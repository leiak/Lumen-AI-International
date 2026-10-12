package com.lumen.dispatch.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.extension.outbox.DomainEvent;
import lombok.Getter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;

/**
 * 配送单 (WmsDeliveryOrder) 状态机迁移事件 (Iteration 4.1.x PR2).
 *
 * <p>由 {@link com.lumen.dispatch.service.impl.DeliveryOrderServiceImpl} 在
 * 9 个状态机迁移 (assign / load / depart / arrive / sign / cancel /
 * raiseException / resolveException RE_DISPATCH / resolveException CANCEL)
 * 之后通过 {@link com.lumen.extension.outbox.EventBus#publish} 写入
 * {@code t_event_outbox}, 同一事务回滚则事件行也回滚 (outbox 模式契约).
 *
 * <p>payload 字段 (经 {@code JSONUtil.toJsonStr} 序列化为 JSON 存进
 * {@code t_event_outbox.payload} 列):
 * <ul>
 *   <li>{@code orderId}, {@code no} — 配送单标识</li>
 *   <li>{@code tenantId} — 多租户隔离键</li>
 *   <li>{@code fromState}, {@code toState}, {@code action} — 状态机迁移三元组</li>
 *   <li>{@code vehicleId}, {@code driverId} — 派车快照</li>
 *   <li>{@code totalWeightKg}, {@code totalVolumeM3}, {@code stopCount} — 货量快照</li>
 *   <li>{@code exceptionId} — 关联的异常工单 (raiseException 后会写)</li>
 *   <li>{@code actor} — 当前操作人 (Spring Security {@code Authentication.getName()};
 *       未登录则 {@code "system"})</li>
 *   <li>{@code occurredAt} — 事件发生时间 (由 {@link DomainEvent} 基类生成)</li>
 * </ul>
 */
@Getter
public class DeliveryOrderStateChangedEvent extends DomainEvent {

    private final Long tenantId;
    private final Long orderId;
    private final String no;
    private final String fromState;
    private final String toState;
    private final String action;
    private final Long vehicleId;
    private final Long driverId;
    private final java.math.BigDecimal totalWeightKg;
    private final java.math.BigDecimal totalVolumeM3;
    private final Integer stopCount;
    private final Long exceptionId;
    private final String actor;

    @JsonCreator
    public DeliveryOrderStateChangedEvent(
            @JsonProperty("tenantId") Long tenantId,
            @JsonProperty("orderId") Long orderId,
            @JsonProperty("no") String no,
            @JsonProperty("fromState") String fromState,
            @JsonProperty("toState") String toState,
            @JsonProperty("action") String action,
            @JsonProperty("vehicleId") Long vehicleId,
            @JsonProperty("driverId") Long driverId,
            @JsonProperty("totalWeightKg") java.math.BigDecimal totalWeightKg,
            @JsonProperty("totalVolumeM3") java.math.BigDecimal totalVolumeM3,
            @JsonProperty("stopCount") Integer stopCount,
            @JsonProperty("exceptionId") Long exceptionId,
            @JsonProperty("actor") String actor) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(toState, "toState");
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.no = no;
        this.fromState = fromState;
        this.toState = toState;
        this.action = action;
        this.vehicleId = vehicleId;
        this.driverId = driverId;
        this.totalWeightKg = totalWeightKg;
        this.totalVolumeM3 = totalVolumeM3;
        this.stopCount = stopCount;
        this.exceptionId = exceptionId;
        this.actor = actor;
    }

    @Override
    public String eventType() {
        return DispatchConstants.EVENT_TYPE_DELIVERY_ORDER_STATE_CHANGED;
    }

    @Override
    public String aggregateType() {
        return DispatchConstants.AGGREGATE_TYPE_DELIVERY_ORDER;
    }

    @Override
    public String aggregateId() {
        return String.valueOf(orderId);
    }

    @Override
    public Long tenantId() {
        return tenantId;
    }

    // ================================================================
    // Factory
    // ================================================================

    /**
     * 从配送单实体构建迁移事件.
     *
     * <p>{@code actor} 自动从 Spring Security 取当前登录用户名 (审计切面同款
     * 模式), 未登录则降级为 {@code "system"} (PR4 行为).
     *
     * @param order   已写入 DB 的 {@link WmsDeliveryOrder} 实体
     * @param from    迁移前状态 (e.g. {@code "PENDING_DISPATCH"})
     * @param to      迁移后状态 (e.g. {@code "DISPATCHED"})
     * @param action  状态机 action (e.g. {@code "dispatch"}, {@code "reDispatch"})
     */
    public static DeliveryOrderStateChangedEvent of(WmsDeliveryOrder order, String from, String to, String action) {
        return new DeliveryOrderStateChangedEvent(
                order.getTenantId(),
                order.getId(),
                order.getNo(),
                from,
                to,
                action,
                order.getVehicleId(),
                order.getDriverId(),
                order.getTotalWeightKg(),
                order.getTotalVolumeM3(),
                order.getStopCount(),
                order.getExceptionId(),
                resolveActor());
    }

    /**
     * 从 Spring Security {@code Authentication} 解析当前操作人.
     * 匿名 / 未登录返回 {@code "system"}.
     */
    private static String resolveActor() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(auth.getPrincipal())) {
                return auth.getName();
            }
        } catch (Exception ignored) {
            // SecurityContextHolder 在非 Spring 上下文 (e.g. 单元测试) 下可能抛 NPE
        }
        return "system";
    }
}