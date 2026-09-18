package com.lumen.masterdata.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_country")
public class SysCountry extends BaseEntity {
    private String code;     // ISO 3166-1 alpha-2
    private String nameCn;
    private String nameEn;
}