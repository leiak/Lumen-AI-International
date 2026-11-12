package com.lumen.order.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lumen.extension.outbox.DomainEvent;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 委托单状态变更事件 (Iteration 4.6).
 *
 * <p><b>eventType 单一 + action 字段区分语义</b> —— 设计文档
 * ({@code docs/business/order/05-委托单业务细化-v4.0.md}) 用的是
 * {@code order.submitted} / {@code order.confirmed} 等多个 eventType, 但
 * {@link DomainEvent#eventType()} 是单值契约, 做多个子类会显著放大代码面。
 * 本类用一个 {@code eventType = "order.state.changed"} + {@code action} 承载全部 9 个动作
 * (submit / confirm / return / cancel / void / start_operation / deliver / settle / close)。
 *
 * <p><b>payload 由 Hutool 写出、由 Jackson 读回</b> —— 写入侧
 * {@code DefaultEventBus.publish} 用 {@code JSONUtil.toJsonStr(event)} 落
 * {@code t_event_outbox.payload} 列, 读取侧 {@code OutboxDispatcher.parseEvent}
 * 用 Jackson 反序列化回具体类。Hutool 走 JavaBean getter 约定, 所以业务字段
 * <b>必须</b>是 {@code getXxx} 形式的公有方法 —— 类上的 {@code @Getter} 会自动生成。
 *
 * <p><b>构造器必须打 {@code @JsonCreator} + {@code @JsonProperty}</b> ——
 * 本类没有无参构造器, {@code OutboxDispatcher} 的 mapper
 * (字段可见性放宽成 {@code PropertyAccessor.FIELD, ANY}, getter 可见性关成
 * {@code NONE}) 也<b>不能替 Jackson 造对象</b>: 构造器不带 {@code @JsonCreator} 时
 * Jackson 找不到可用的实例化入口, 抛
 * {@code InvalidDefinitionException("no Creators, like default constructor, exist")}
 * → dispatcher 捕获 → 重试 → 耗尽 → outbox 行变 {@code DEAD_LETTER} (毒丸)。
 * 既有 {@code CountryStateChangedEvent} / {@code DeliveryOrderStateChangedEvent}
 * 全是这个写法, 本类照抄。
 *
 * <p><b>基类的四个抽象方法没有 {@code get} 前缀</b> ——
 * {@link DomainEvent#eventType()} / {@link DomainEvent#aggregateType()} /
 * {@link DomainEvent#aggregateId()} / {@link DomainEvent#tenantId()} 由子类覆写;
 * {@link DomainEvent#aggregateId()} 的返回类型是 {@code String}, 不是 {@code Long}。
 * 基类<b>没有</b> {@code tenantId} 构造器, {@code tenantId} 必须由子类自己存字段
 * 并覆写 {@link #tenantId()} 返回。
 *
 * <p><b>字段名不要随便改</b> —— 下游 {@code lumen-finance} 的
 * {@code BusinessEventListener} 用 {@code event.getClass().getSimpleName()} 判定事件类型、
 * 用反射取 getter (见其 {@code invokeGetter}: {@code cls.getMethod("getXxx")}),
 * 拿不到 getter 时只打 debug 日志并按 null 处理 —— 改名不会报错, 只会静默丢值。
 * <br>
 * <b>本轮 finance 已接入本事件</b> —— {@code BusinessEventListener.mapToBillingEvent}
 * 的 switch 里有 {@code case "OrderStateChangedEvent" -> mapOrderEvent(event);},
 * 由 {@code mapOrderEvent} (仅 {@code toState=DELIVERED} 时映射) 反射取
 * {@code getToState} / {@code getTotalWeightKg} / {@code getTotalQty} /
 * {@code getEstimatedAmount} / {@code getOrderId} / {@code getCustomerId},
 * 分别映射成 {@code weight} / {@code quantity} / {@code amount} /
 * {@code businessId} / {@code counterpartyId}。
 * 所以字段名必须与真实事件类的 {@code getXxx} 约定逐字一致:
 * 改名不会报错, 只会让 finance 侧反射拿不到 getter 而静默丢值 (只打 debug 日志, 按 null 处理)。
 *
 * <p><b>{@code reason} 字段承载「为什么转」</b> —— 取消 / 作废 / 关账的原因同时落在
 * 订单行的 {@code cancel_reason} / {@code void_reason} / {@code close_reason} 三列
 * (V42 里这三列都是 {@code VARCHAR(255)}); <b>退回 (return) 的原因只存在于本事件里</b> ——
 * V42 的 {@code t_order} 没有 {@code return_reason} 列, 本轮不为此加列
 * (加列要动迁移脚本, 超出本轮范围)。
 *
 * <p><b>{@code actor} 本轮恒为 null</b> —— 还没接入操作人上下文 (登录态 / 员工 ID),
 * 字段先留着, 将来接上时不用改事件签名。
 */
@Getter
public class OrderStateChangedEvent extends DomainEvent {

    private final Long tenantId;
    private final Long orderId;
    private final String orderNo;
    private final String fromState;
    private final String toState;
    private final String action;
    private final String reason;
    private final Long customerId;
    private final BigDecimal totalWeightKg;
    private final BigDecimal totalVolumeCbm;
    private final BigDecimal totalQty;
    private final BigDecimal estimatedAmount;
    private final String currencyCode;
    private final String actor;

    @JsonCreator
    public OrderStateChangedEvent(
            @JsonProperty("tenantId") Long tenantId,
            @JsonProperty("orderId") Long orderId,
            @JsonProperty("orderNo") String orderNo,
            @JsonProperty("fromState") String fromState,
            @JsonProperty("toState") String toState,
            @JsonProperty("action") String action,
            @JsonProperty("reason") String reason,
            @JsonProperty("customerId") Long customerId,
            @JsonProperty("totalWeightKg") BigDecimal totalWeightKg,
            @JsonProperty("totalVolumeCbm") BigDecimal totalVolumeCbm,
            @JsonProperty("totalQty") BigDecimal totalQty,
            @JsonProperty("estimatedAmount") BigDecimal estimatedAmount,
            @JsonProperty("currencyCode") String currencyCode,
            @JsonProperty("actor") String actor) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(toState, "toState");
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.fromState = fromState;
        this.toState = toState;
        this.action = action;
        this.reason = reason;
        this.customerId = customerId;
        this.totalWeightKg = totalWeightKg;
        this.totalVolumeCbm = totalVolumeCbm;
        this.totalQty = totalQty;
        this.estimatedAmount = estimatedAmount;
        this.currencyCode = currencyCode;
        this.actor = actor;
    }

    @Override
    public String eventType() {
        return "order.state.changed";
    }

    @Override
    public String aggregateType() {
        return "ORDER";
    }

    /** {@link DomainEvent#aggregateId()} 的签名是 {@code String} —— 必须转字符串。 */
    @Override
    public String aggregateId() {
        return String.valueOf(orderId);
    }

    @Override
    public Long tenantId() {
        return tenantId;
    }
}
