package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {
    private String code;
    private String name;
    private String dataScope;     // ALL/DEPT/DEPT_AND_SUB/SELF/CUSTOM
    private Integer status;
}