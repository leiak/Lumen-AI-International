package com.lumen.dispatch.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 配送站点 (Delivery Stop — one order, multiple stops).
 *   stop_no >= 1
 *   status  PENDING/ARRIVED/COMPLETED/FAILED  (DB-level constraint)
 *
 * <p>签收照片 URL 存放在 {@link WmsDeliveryOrder#signPhotoUrl}（PR4 引入）,
 * 本表仅记录站点的过程事件.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_delivery_stop")
public class WmsDeliveryStop extends BaseEntity {

    private Long orderId;

    private Integer stopNo;

    private String address;

    private String contactName;

    private String contactPhone;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private LocalDateTime plannedAt;

    private LocalDateTime arrivedAt;

    private LocalDateTime completedAt;

    private String status;

    private String failureReason;
}