package com.lumen.dispatch.event;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.dto.DeliveryOrderDtos.CreateDeliveryOrderRequest;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsDeliveryStop;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.mapper.WmsDeliveryStopMapper;
import com.lumen.dispatch.service.DeliveryOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 订阅 WMS outbound.shipped 事件 — 自动建 PENDING_DISPATCH 配送单 + N 个 stop 行.
 *
 * <p>事件由 lumen-wms OutboundServiceImpl.ship(...) 在事务内 publish;
 * 该 listener @Async("outboxExecutor") 异步消费, 不影响主链路出货.
 *
 * <p>PR4: 事件本身已携带 customerAddress/contactName/phone/stopCount, 因此本
 * listener 不需要跨模块注入 WMS mapper (lumen-wms 依赖 lumen-dispatch, 反向
 * 引用会造成循环). 实际地址/行数在 publisher 端 (OutboundServiceImpl) 拉好.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WmsOutboundShippedListener {

    private final DeliveryOrderService deliveryOrderService;
    private final WmsDeliveryOrderMapper orderMapper;
    private final WmsDeliveryStopMapper deliveryStopMapper;

    @Async("outboxExecutor")
    @EventListener
    public void onOutboundShipped(OutboundShippedEvent event) {
        log.info("Received OutboundShipped: tenantId={} outboundId={} outboundNo={} stops={}",
                event.getTenantId(), event.getOutboundId(), event.getOutboundNo(), event.getStopCount());
        try {
            TenantContext.set(event.getTenantId());

            int stopCount = event.getStopCount() != null && event.getStopCount() > 0
                    ? event.getStopCount() : 1;

            // 1. 创建配送单 (PENDING_DISPATCH).
            CreateDeliveryOrderRequest req = new CreateDeliveryOrderRequest();
            req.setSourceNo(event.getOutboundNo());
            req.setSourceType("OUTBOUND");
            req.setWarehouseId(event.getWarehouseId());
            // PR4 placeholder: WmsItem/WmsOutboundLine 没有 weight/volume 字段,
            // 后续接货品主数据后补全. 设为非零以便 RoutingEngine 容量校验通过.
            req.setTotalWeightKg(BigDecimal.ONE);
            req.setTotalVolumeM3(BigDecimal.ONE);
            req.setStopCount(stopCount);
            req.setRemark("auto-created from outbound.shipped " + event.getOutboundNo());

            WmsDeliveryOrder order = deliveryOrderService.create(req);

            // 4.5B.2 T2.7: 传播 customerId (上游 wms_outbound_note.customer_id 由事件携带).
            // CreateDeliveryOrderRequest 当前不暴露 customerId 字段, 此处显式回写
            // 一遍 + updateById, 让 DO 可被 portal 按 customer 过滤. 事件无
            // pickup window 字段, planned_pickup_window_* 保持 null (实体默认值).
            if (event.getCustomerId() != null) {
                order.setCustomerId(event.getCustomerId());
                orderMapper.updateById(order);
            }

            // 2. 创建 N 个 PENDING 停站点 (NN by stop_no). 真实客户地址从事件读,
            //    避免 listener 跨模块依赖 WMS mapper.
            for (int i = 1; i <= stopCount; i++) {
                WmsDeliveryStop stop = new WmsDeliveryStop();
                stop.setOrderId(order.getId());
                stop.setStopNo(i);
                stop.setAddress(event.getCustomerAddress() != null
                        ? event.getCustomerAddress() : "客户地址 (待补全)");
                stop.setContactName(event.getCustomerContactName());
                stop.setContactPhone(event.getCustomerPhone());
                stop.setStatus("PENDING");
                deliveryStopMapper.insert(stop);
            }

            log.info("Delivery order auto-created: id={} from outbound={} ({} stop(s))",
                    order.getId(), event.getOutboundNo(), stopCount);
        } catch (Exception e) {
            // 事件 listener 永远不抛 — 失败仅记日志, 由 outbox 兜底重试
            log.error("Failed to auto-create delivery order from outbound={}",
                    event.getOutboundNo(), e);
        } finally {
            TenantContext.clear();
        }
    }
}
