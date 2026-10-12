package com.lumen.dispatch.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 配送异常工单 (Delivery Exception Work Order).
 *   type   REJECTED/OVERTIME/DAMAGE/ADDR_ERR (DB-level constraint)
 *   status OPEN/INVESTIGATING/RESOLVED/CLOSED/CANCELLED (PR2 状态机)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_delivery_exception")
public class WmsDeliveryException extends BaseEntity {

    private Long orderId;

    private String type;

    private String status;

    private String description;

    private String handler;

    private LocalDateTime resolvedAt;
}