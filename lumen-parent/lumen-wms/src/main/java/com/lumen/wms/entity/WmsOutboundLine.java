package com.lumen.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 出库单行项目.
 *
 * <p>每行对应一种货品的预期出库数量；状态机驱动的拣货数量 {@code pickedQty}
 * 由 T4.2 写入.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_outbound_line")
public class WmsOutboundLine extends BaseEntity {

    private Long noteId;

    private Integer lineNo;

    private Long itemId;

    private BigDecimal qty;

    private BigDecimal pickedQty;

    private Long sourceLocationId;
}
