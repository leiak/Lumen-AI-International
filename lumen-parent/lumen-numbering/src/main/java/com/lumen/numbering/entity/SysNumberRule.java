package com.lumen.numbering.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_number_rule")
public class SysNumberRule extends BaseEntity {
    private String code;
    private String prefix;
    private String dateFormat;
    private Integer seqLength;
    private String resetPolicy;
    private Long currentValue;
    private String description;
}
