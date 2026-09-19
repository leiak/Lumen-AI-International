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
    /**
     * 状态：sys_country 状态机的状态字段（V10 新增）。
     * 取值集合：{@code t_state_transition} 中 sys_country 出现的状态码（DRAFT/ACTIVE/FROZEN/VOID）。
     * 默认 DRAFT；新流程要求显式调用 {@code SysCountryService.changeState(...)} 发布到 ACTIVE。
     */
    private String status;
}