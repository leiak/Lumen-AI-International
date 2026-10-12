package com.lumen.dispatch.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 配送明细 (Delivery Line — 与 outbound line 对应).
 *   qty       > 0  (DB-level constraint)
 *   weight_kg >= 0 (DB-level constraint)
 *   volume_m3 >= 0 (DB-level constraint)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_delivery_line")
public class WmsDeliveryLine extends BaseEntity {

    private Long orderId;

    private Long itemId;

    private BigDecimal qty;

    private BigDecimal weightKg;

    private BigDecimal volumeM3;
}