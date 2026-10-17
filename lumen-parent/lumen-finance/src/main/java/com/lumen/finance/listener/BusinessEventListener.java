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
 * <p><b>失败处理:</b> listener 永不抛出 (catch + log) — 失败由 outbox 兜底
 * 重试链路处理. 这一点与 lumen-cs
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
            // listener 永不抛 — 失败仅记日志, 由 outbox 兜底重试
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
     * <p>WMS 适配 (TODO): lumen-wms 3.0 当前未发布 WmsInboundCompletedEvent /
     * WmsOutboundCompletedEvent. PR3 末尾将追加 WmsEventPoller (5min @Scheduled)
     * 替代 WMS listener. 此处先留扩展点 — 一旦 WMS 事件落地, 只需在此处增加 case 即可.
     */
    private BillingEventDto mapToBillingEvent(DomainEvent event) {
        String simpleName = event.getClass().getSimpleName();
        return switch (simpleName) {
            case "DeliveryOrderStateChangedEvent" -> mapDeliveryOrderEvent(event);
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
