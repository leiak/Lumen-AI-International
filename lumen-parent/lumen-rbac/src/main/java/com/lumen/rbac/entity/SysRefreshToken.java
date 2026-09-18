package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_refresh_token")
public class SysRefreshToken {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String jti;
    private Long userId;
    private Long tenantId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private Integer revoked;       // 0=有效 1=撤销
}