package com.lumen.masterdata.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_uom")
public class SysUom extends BaseEntity {
    private String code;     // KG/CBM/CTN
    private String name;
    private String dimension; // WEIGHT/VOLUME/QUANTITY
}