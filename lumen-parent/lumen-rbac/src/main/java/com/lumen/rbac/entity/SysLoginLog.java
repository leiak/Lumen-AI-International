package com.lumen.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_login_log")
public class SysLoginLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String username;
    private Long userId;
    private Long tenantId;
    private String ip;
    private String userAgent;
    private Integer status;        // 1=成功 0=失败
    private String errorMsg;
    private LocalDateTime createdAt;
}