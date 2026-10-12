package com.lumen.dispatch.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 承运人主数据 (Carrier master — 自有车队 / 外协).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_carrier")
public class WmsCarrier extends BaseEntity {

    private String code;

    private String name;

    private String contact;

    private String status;
}