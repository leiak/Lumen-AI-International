package com.lumen.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 入库单行项目.
 *
 * <p>每行对应一种货品的预期收货数量；状态机驱动的收货数量 {@code receivedQty}
 * 与上架数量 {@code putawayQty} 由后续任务(T3.4)写入.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_inbound_line")
public class WmsInboundLine extends BaseEntity {

    private Long noteId;

    private Integer lineNo;

    private Long itemId;

    private BigDecimal qty;

    private BigDecimal receivedQty;

    private Long putawayLocationId;

    private BigDecimal putawayQty;
}
