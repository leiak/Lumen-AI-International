package com.lumen.dispatch.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * WMS 出库单发货事件 — Dispatch 模块订阅后自动建 PENDING_DISPATCH 配送单.
 *
 * <p>由 {@code OutboundServiceImpl.ship(...)} 发布；不在 lumen-wms 模块
 * 暴露对外 API — WMS 内部 {@code ApplicationEventPublisher.publishEvent(...)}
 * 调用，跨模块 listener 自动接收.
 *
 * <p>事件结构遵循 outbox payload JSON 同形（PR5 接 outbox dispatcher 时
 * 把同一对象序列化进 EventOutbox 行）:
 *   tenantId / outboundId / outboundNo / customerId / warehouseId / occurredAt
 *
 * <p>PR4: 新增 customerAddress / customerContactName / customerPhone / stopCount
 * 四个字段，让 listener 不必跨模块依赖 WMS 即可建出可用的 stop 行.
 */
@Getter
public class OutboundShippedEvent extends ApplicationEvent {

    private final Long tenantId;
    private final Long outboundId;
    private final String outboundNo;
    private final Long customerId;
    private final Long warehouseId;
    private final String customerAddress;
    private final String customerContactName;
    private final String customerPhone;
    private final Integer stopCount;
    private final LocalDateTime occurredAt;

    public OutboundShippedEvent(Object source,
                                Long tenantId,
                                Long outboundId,
                                String outboundNo,
                                Long customerId,
                                Long warehouseId,
                                String customerAddress,
                                String customerContactName,
                                String customerPhone,
                                Integer stopCount) {
        super(source);
        this.tenantId = tenantId;
        this.outboundId = outboundId;
        this.outboundNo = outboundNo;
        this.customerId = customerId;
        this.warehouseId = warehouseId;
        this.customerAddress = customerAddress;
        this.customerContactName = customerContactName;
        this.customerPhone = customerPhone;
        this.stopCount = stopCount;
        this.occurredAt = LocalDateTime.now();
    }
}
