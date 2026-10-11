package com.lumen.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 库位主数据.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_location")
public class WmsLocation extends BaseEntity {

    private Long warehouseId;

    private String code;

    private String zone;

    private BigDecimal capacity;

    private String status;
}
