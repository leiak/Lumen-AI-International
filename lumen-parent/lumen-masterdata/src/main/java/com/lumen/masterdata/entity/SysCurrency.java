package com.lumen.masterdata.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_currency")
public class SysCurrency extends BaseEntity {
    private String code;     // USD/CNY/EUR
    private String name;
    private Integer scale;   // 小数位数
}