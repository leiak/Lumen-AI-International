package com.lumen.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 出库单主表.
 *
 * <p>状态字段 {@code status} 字符串取值对应 {@link com.lumen.wms.enums.WmsOutboundStatus} 枚举；
 * 状态机迁移在 T4.2 中实现.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_outbound_note")
public class WmsOutboundNote extends BaseEntity {

    private String no;

    private Long customerId;

    private Long warehouseId;

    private String status;

    private LocalDateTime requestedAt;

    private LocalDateTime shippedAt;

    private String carrier;

    private String trackingNo;

    private String remark;
}
