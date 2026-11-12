package com.lumen.finance.listener;

import com.lumen.common.tenant.TenantContext;
import com.lumen.extension.outbox.DomainEvent;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingEventDto;
import com.lumen.finance.service.BillingEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 计费业务事件监听器 (Iteration 4.3.1, PR3-T3.2).
 *
 * <p>订阅上游 outbox 事件, 把业务事件转换为 {@link BillingEventDto} 后调用
 * {@link BillingEngineService#accrue(BillingEventDto)}.
 *
 * <p><b>当前覆盖 (4.3.1 PR3):</b>
 * <ul>
 *   <li>{@code DeliveryOrderStateChangedEvent} (lumen-dispatch) → 仅当 {@code toState=SIGNED}
 *       (配送完成) 时, 取 {@code orderId/totalWeightKg/totalVolumeM3} 作为 env,
 *       业务类型 LAST_MILE, direction=RECEIVABLE.</li>
 * </ul>
 *
 * <p><b>4.6 新增:</b>
 * <ul>
 *   <li>{@code OrderStateChangedEvent} (lumen-order 货运委托单) → 仅当
 *       {@code toState=DELIVERED} (交付) 时才计费: 取
 *       {@code totalWeightKg/totalQty/estimatedAmount} 作为 env,
 *       {@code businessId=orderId}, 对手方 = {@code customerId} (CUSTOMER),
 *       业务类型 ORDER, direction=RECEIVABLE.</li>
 * </ul>
 *
 * <p><b>ORDER 分支有两个必须知道的前提</b> (详见 {@link #mapOrderEvent} 的 javadoc):
 * ① 必须先由运维建一条可命中的 ORDER 计费规则, 否则走到 DELIVERED 只记 NO_RULE、不产生提单;
 * ② order 事务回滚后可能留下孤儿提单 (outbox 的 publishEvent 是当场异步投递的, 不等 order 提交) ——
 * 本轮只记录, 不改行为。
 *
 * <p><b>WMS 事件 (TODO):</b> lumen-wms 3.0 当前未提供
 * {@code WmsInboundCompletedEvent} / {@code WmsOutboundCompletedEvent} (见 plan T3.2 note).
 * PR3 末尾计划追加 {@code WmsEventPoller} (5min {@code @Scheduled}) 替代 WMS 事件
 * listener — 暂留 TODO. 在 PR3 完成 controllers 之后 (T3.5 之后) 会做追加实现.
 *
 * <p><b>异步执行:</b> 走 {@code outboxExecutor} (lumen-extension AsyncConfig),
 * 避免阻塞 lumen-dispatch 业务事务.
 *
 * <p><b>TenantContext 处理:</b> 异步 listener 在 {@code outboxExecutor} 线程上
 * 执行, ThreadLocal 不会继承. 必须从事件取值并 set 到 TenantContext, 否则
 * {@code MybatisPlusConfig} 的 {@code TenantLineInnerInterceptor} 会以 tid=0
 * 过滤, 看不到非 tenant=0 行.
 *
 * <p><b>失败处理:</b> listener 永不抛出 (catch + log) — 失败只记日志, 由调用方自行决定
 * 如何处理. <b>没有 outbox 兜底重试链路</b>: 本 listener 是
 * {@code @Async("outboxExecutor")}, 对它 {@code publisher.publishEvent(event)} 立即返回,
 * 异常回不到 {@code OutboxDispatcher.dispatch()}, outbox 行照样被标 DONE.
 * 「listener 永不抛出」这一点与 lumen-cs
 * {@code DeliveryExceptionStateChangedEventListener} 保持一致.
 *
 * <p><b>解耦设计:</b> 本 listener 不直接 import lumen-dispatch 类 (lumen-finance
 * 没有 lumen-dispatch 依赖). 通过 {@code event.getClass().getSimpleName()} 判定事件
 * 类型, 通过反射调用 getter. 这样新增事件类型时只需在本类扩展一个 case + getter 列表.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessEventListener {

    private final BillingEngineService engine;

    @EventListener
    @Async("outboxExecutor")
    public void onEvent(DomainEvent event) {
        if (event == null) return;
        try {
            TenantContext.set(event.tenantId());
            BillingEventDto dto = mapToBillingEvent(event);
            if (dto == null) {
                // 非计费触发事件 (e.g. 派车未完成 / WMS 暂未接入) → 静默
                log.debug("Skip billing for event {} (class={}, tenantId={})",
                    event.getEventId(), event.getClass().getSimpleName(), event.tenantId());
                return;
            }
            engine.accrue(dto);
        } catch (Exception ex) {
            // listener 永不抛 — 失败仅记日志, 由调用方自行决定如何处理 (无 outbox 重试链路)
            log.error("Failed to accrue billing for event {} (class={}, tenantId={})",
                event.getEventId(), event.getClass().getSimpleName(), event.tenantId(), ex);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 把 DomainEvent 转换为 BillingEventDto. 当前只处理:
     * <ul>
     *   <li>{@code DeliveryOrderStateChangedEvent}: 仅 {@code toState=SIGNED} (配送完成)</li>
     * </ul>
     *
     * <p>其他事件类型 (e.g. {@code DeliveryExceptionStateChangedEvent},
     * 未实现的 WMS 事件) 返回 null, listener 视为"非计费触发"静默跳过.
     *
     * <p>4.6 新增: {@code OrderStateChangedEvent} (货运委托单) → 仅
     * {@code toState=DELIVERED} (交付) 时映射.
     *
     * <p>WMS 适配 (TODO): lumen-wms 3.0 当前未发布 WmsInboundCompletedEvent /
     * WmsOutboundCompletedEvent. PR3 末尾将追加 WmsEventPoller (5min @Scheduled)
     * 替代 WMS listener. 此处先留扩展点 — 一旦 WMS 事件落地, 只需在此处增加 case 即可.
     */
    private BillingEventDto mapToBillingEvent(DomainEvent event) {
        String simpleName = event.getClass().getSimpleName();
        return switch (simpleName) {
            case "DeliveryOrderStateChangedEvent" -> mapDeliveryOrderEvent(event);
            case "OrderStateChangedEvent" -> mapOrderEvent(event);
            // TODO(WMS): add cases for "WmsInboundCompletedEvent" and
            //            "WmsOutboundCompletedEvent" once lumen-wms publishes them.
            default -> null;
        };
    }

    /**
     * 配送单状态事件 → BillingEventDto (仅 SIGNED 触发计费).
     *
     * <p>env 提取规则 (全部通过反射取 getter):
     * <ul>
     *   <li>{@code weight}     ← {@code getTotalWeightKg()} (BigDecimal)</li>
     *   <li>{@code distance}   ← {@code getDistanceKm()} (若无则 null)</li>
     *   <li>{@code quantity}   ← {@code getStopCount()} (站点数)</li>
     *   <li>{@code amount}     ← 0 (运单金额通常不在事件里, 留给上游注入或 PR3 后续增强)</li>
     * </ul>
     *
     * <p>反射调用容错: 任何 getter 缺失/抛异常 → 对应 env 字段为 null (不抛),
     * 让 Aviator 走 "null * anything = null" 路径被 eval 阶段捕获 (落到 OPEN_FAILED).
     */
    private BillingEventDto mapDeliveryOrderEvent(DomainEvent event) {
        Object toState = invokeGetter(event, "getToState", "toState");
        if (!FinanceConstants.FIN_TRIGGER_STATE_LAST_MILE.equals(toState)) {
            return null;
        }

        Map<String, Object> env = new HashMap<>();
        putIfPresent(env, "weight",   invokeGetter(event, "getTotalWeightKg", "weight"));
        putIfPresent(env, "distance", invokeGetter(event, "getDistanceKm", "distance"));
        putIfPresent(env, "quantity", invokeGetter(event, "getStopCount", "quantity"));
        putIfPresent(env, "amount",   BigDecimal.ZERO);

        Long businessId = asLong(invokeGetter(event, "getOrderId", "businessId"));
        return new BillingEventDto(
            event.tenantId(),
            event.getEventId(),                                       // DomainEvent.eventId
            FinanceConstants.BIZ_LAST_MILE,
            businessId,
            FinanceConstants.DIR_RECEIVABLE,
            null,                                                     // counterpartyId 待 PR3 末尾增强
            FinanceConstants.CP_CUSTOMER,
            env
        );
    }

    /**
     * 货运委托单状态事件 → BillingEventDto (仅 DELIVERED 触发计费).
     *
     * <p>env 提取规则 (全部通过反射取 getter):
     * <ul>
     *   <li>{@code weight}   ← {@code getTotalWeightKg()} (BigDecimal)</li>
     *   <li>{@code quantity} ← {@code getTotalQty()} (件数)</li>
     *   <li>{@code amount}   ← {@code getEstimatedAmount()} (委托单服务项金额合计)</li>
     *   <li>{@code distance} ← 不提供 (委托单没有距离维度)</li>
     * </ul>
     *
     * <p><b>env 的 key 必须落在 BillingScriptEngine 的沙箱白名单内</b> ——
     * {@code WHITELIST_VARIABLES} 只有 9 个:
     * {@code amount / weight / distance / quantity / customer_level / zone /
     * service_type / is_weekend / is_holiday}. env 里出现白名单外的 key 时,
     * {@code BillingScriptEngine.eval} 抛 {@code FinanceException_UnknownVar};
     * {@code accrue} 捕获后写 OPEN_FAILED 取证行并抛
     * {@code FinanceException(EXPRESSION_INVALID)}. 本方法只放
     * weight / quantity / amount 三个, 都在白名单内.
     *
     * <p>businessType = {@code ORDER} —— {@code accrue} 按 business_type 查
     * {@code t_billing_rule} 的 ACTIVE 规则; 没有匹配规则时走 markNoRule 分支
     * (event_log 标 NO_RULE, 不写 accrual), 这是预期行为, 不是 bug.
     *
     * <p><b>前置条件: 必须先由运维建好可命中的计费规则, 否则「交付即计费」是空转。</b>
     * {@code accrue} 匹配规则用的谓词 (见 {@code BillingEngineServiceImpl.accrue}) 是:
     * {@code tenant_id = <事件所属租户>} + {@code business_type = 'ORDER'} +
     * {@code direction = 'RECEIVABLE'} + {@code status = 'ACTIVE'} +
     * 生效日期覆盖当天, <b>并且该规则至少有一条 rule_item</b>。
     * 一条都没建的话: 委托单走到 DELIVERED → 本 listener 映射成功 → accrue 查不到规则 →
     * 走 markNoRule 分支 → {@code t_billing_event_log} 只记 {@code NO_RULE},
     * {@code t_billing_accrual} <b>一行不写</b> —— <b>货交了、钱没计</b>
     * (event_log 里留有 NO_RULE 痕迹可查)。
     *
     * <p><b>关于 counterparty_type:</b> {@code t_billing_rule.counterparty_type} 这一列
     * <b>不参与 accrue 的规则匹配</b> —— 上面那几条谓词里没有它;
     * 而且 {@code t_billing_accrual} 表<b>根本没有 counterparty_type 列</b>
     * (只有 {@code counterparty_id}, 取自事件的 {@code customerId}),
     * 所以这一列纯属规则元数据, <b>不是</b>「这条规则会不会被命中」的开关:
     * 建规则时仍应按业务语义填 {@code CUSTOMER} (委托单的计费对手方是客户),
     * 但即便填成别的值, accrue 一样会命中这条 ORDER 规则。
     *
     * <p><b>已知风险: order 事务回滚后可能留下孤儿提单 (重复计费)。</b>
     * {@code DefaultEventBus.publish} 在写 outbox 行的<b>同一个事务</b>里还<b>当场</b>
     * {@code publisher.publishEvent(event)}; 而本 listener 是
     * {@code @EventListener @Async("outboxExecutor")} —— 它在<b>另一个线程</b>上并行跑,
     * {@code engine.accrue} 用<b>自己的事务</b>。于是提单<b>先于 order 事务提交落库</b>,
     * outbox 的「同事务」保证在这条路上被绕过。
     * 触发场景: order 的 deliver 事务发完事件后, {@code toDetail(order)} 抛异常或提交阶段失败 →
     * order 事务回滚 (状态仍是 IN_OPERATION, outbox 行也一起没了, 不会重投) →
     * 但 finance 的 accrual <b>已经提交</b> → 之后这单再交付一次 → 新 eventId →
     * 又一条 accrual → <b>同一张单两条提单 (重复计费)</b>。
     * <br>
     * <b>本轮不修</b>: 改 {@code DefaultEventBus} 不当场 publish 会影响 dispatch / finance
     * 的既有行为 (dispatch 那条 LAST_MILE/SIGNED 的路是同一个形状), 牵动面超出 4.6;
     * 且这属于「全局事件投递语义」改造, 需要单独设计。留待后续切片统一处理。
     */
    private BillingEventDto mapOrderEvent(DomainEvent event) {
        Object toState = invokeGetter(event, "getToState", "toState");
        if (!FinanceConstants.FIN_TRIGGER_STATE_ORDER.equals(toState)) {
            return null;
        }

        Map<String, Object> env = new HashMap<>();
        putIfPresent(env, "weight",   invokeGetter(event, "getTotalWeightKg", "weight"));
        putIfPresent(env, "quantity", invokeGetter(event, "getTotalQty", "quantity"));
        putIfPresent(env, "amount",   invokeGetter(event, "getEstimatedAmount", "amount"));

        Long businessId = asLong(invokeGetter(event, "getOrderId", "businessId"));
        Long counterpartyId = asLong(invokeGetter(event, "getCustomerId", "counterpartyId"));
        return new BillingEventDto(
            event.tenantId(),
            event.getEventId(),
            FinanceConstants.BIZ_ORDER,
            businessId,
            FinanceConstants.DIR_RECEIVABLE,
            counterpartyId,
            FinanceConstants.CP_CUSTOMER,
            env
        );
    }

    /**
     * 反射调用目标对象上的 getter. 任何异常 → null.
     * 用于解耦: lumen-finance 不需要 import 上游事件类的具体类型, 也能读到字段.
     *
     * <p>异常分级:
     * <ul>
     *   <li>{@link NoSuchMethodException} — 字段在当前事件版本上不存在 → debug (预期路径)</li>
     *   <li>其他异常 — 字段存在但 invoke 抛错 → warn (告知运维, env 字段会缺失)</li>
     * </ul>
     */
    private Object invokeGetter(Object target, String method, String envKey) {
        if (target == null) return null;
        Class<?> cls = target.getClass();
        try {
            Method m = cls.getMethod(method);
            Object v = m.invoke(target);
            if (v != null) return v;
        } catch (NoSuchMethodException e) {
            // field not present on this event version — debug only
            log.debug("invokeGetter({}) absent on {} — env key '{}' missing",
                method, cls.getSimpleName(), envKey);
        } catch (Exception ex) {
            // field present but threw — surface to operators
            log.warn("invokeGetter({}) on {} threw: {} — env key '{}' will be missing",
                method, cls.getSimpleName(), ex.getMessage(), envKey);
        }
        return null;
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    private static void putIfPresent(Map<String, Object> env, String key, Object value) {
        if (value != null) env.put(key, value);
    }
}
