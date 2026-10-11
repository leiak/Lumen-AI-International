package com.lumen.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存账本 (per location × item).
 *   qty         >= 0  (DB-level constraint)
 *   locked_qty  >= 0  (DB-level constraint)
 *   qty         >= locked_qty  (DB-level constraint)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_inventory")
public class WmsInventory extends BaseEntity {

    private Long warehouseId;

    private Long locationId;

    private Long itemId;

    private BigDecimal qty;

    private BigDecimal lockedQty;

    private LocalDateTime lastMovementAt;
}
