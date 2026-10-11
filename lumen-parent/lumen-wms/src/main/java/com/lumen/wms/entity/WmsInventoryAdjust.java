package com.lumen.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 库存调整单主表.
 *
 * <p>状态字段 {@code status} 字符串取值对应 {@link com.lumen.wms.enums.WmsAdjustStatus} 枚举；
 * 审批流由 T5.1 实现.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_inventory_adjust")
public class WmsInventoryAdjust extends BaseEntity {

    private String no;

    private Long warehouseId;

    private String status;

    private String reason;

    private Long approvedBy;

    private LocalDateTime approvedAt;

    private String remark;
}
