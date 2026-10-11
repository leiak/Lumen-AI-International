package com.lumen.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 库存调整单行项目.
 *
 * <p>{@code deltaQty} 可正可负：正值表示盘盈，负值表示盘亏.
 * 行级落地（对 {@code wms_inventory} 账本加减）由 T5.1 写入.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_inventory_adjust_line")
public class WmsInventoryAdjustLine extends BaseEntity {

    private Long adjustId;

    private Integer lineNo;

    private Long itemId;

    private Long locationId;

    private BigDecimal deltaQty;

    private String reasonDetail;
}
