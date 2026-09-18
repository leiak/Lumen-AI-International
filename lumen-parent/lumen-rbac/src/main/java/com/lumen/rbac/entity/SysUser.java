package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {
    private String username;
    private String passwordHash;
    private String realName;
    private String phone;
    private String email;
    private Integer status;       // 1=启用 0=禁用
    private String lastLoginIp;
    private java.time.LocalDateTime lastLoginAt;
}