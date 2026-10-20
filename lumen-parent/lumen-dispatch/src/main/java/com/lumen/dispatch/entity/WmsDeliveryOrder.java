package com.lumen.dispatch.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 配送订单 (Delivery Order aggregate root).
 *   status DISPATCH_ORDER 状态机: PENDING_DISPATCH/DISPATCHED/LOADED/ENROUTE/
 *          ARRIVED/SIGNED/CANCELLED/EXCEPTION (PR2)
 *
 *   total_weight_kg >= 0  (DB-level constraint)
 *   total_volume_m3 >= 0  (DB-level constraint)
 *   stop_count      >= 0  (DB-level constraint)
 *
 * <p>由 WMS {@code outbound.shipped} 事件 (PR4) 自动创建；也可手动创建。
 *   source_no / source_type 记录上游单据，便于反查.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_delivery_order")
public class WmsDeliveryOrder extends BaseEntity {

    private String sourceNo;

    private String sourceType;

    private String no;

    private String status;

    private Long warehouseId;

    /**
     * 客户 ID (4.5A.3 新增, 用于客户门户按 customer 过滤).
     * <p>来自上游 {@code wms_outbound_note.customer_id} (V34 migration 同时 backfill),
     * 上下游 JOIN 链: {@code delivery_order.source_no = outbound_note.no}.
     */
    private Long customerId;

    private Long vehicleId;

    private Long driverId;

    private Long carrierId;

    private LocalDateTime plannedDepartAt;

    /**
     * 商家选取件窗口起点 (4.5B.1 新增). PENDING_DISPATCH 状态由 portal 端
     * createForCustomer 时填入, >= now+2h, < now+30d.
     */
    private LocalDateTime plannedPickupWindowStart;

    /**
     * 商家选取件窗口终点 (4.5B.1 新增). > start, <= start+8h, 同日.
     */
    private LocalDateTime plannedPickupWindowEnd;

    private LocalDateTime actualDepartAt;

    private LocalDateTime arrivedAt;

    private LocalDateTime deliveredAt;

    private BigDecimal totalWeightKg;

    private BigDecimal totalVolumeM3;

    private Integer stopCount;

    private Integer signRequired;

    private String signPhotoUrl;

    private Long exceptionId;

    private String remark;
}