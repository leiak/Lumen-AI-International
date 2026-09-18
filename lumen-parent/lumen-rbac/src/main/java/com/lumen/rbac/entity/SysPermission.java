package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class SysPermission extends BaseEntity {
    private Long parentId;
    private String type;          // MENU/BUTTON/API
    private String code;
    private String name;
    private String path;
    private Integer sortOrder;
    private Integer status;
}