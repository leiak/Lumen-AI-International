package com.lumen.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 货品主数据.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_item")
public class WmsItem extends BaseEntity {

    private String sku;

    private String name;

    private String uom;

    private String barcode;

    private String hazardClass;

    private BigDecimal unitCost;
}
