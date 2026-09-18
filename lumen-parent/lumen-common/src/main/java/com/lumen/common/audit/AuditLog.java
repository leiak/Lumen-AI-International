package com.lumen.common.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_audit_log")
public class AuditLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String traceId;
    private Long userId;
    private Long tenantId;
    private String username;
    private String action;
    private String resource;
    private String resourceId;
    private String method;
    private String uri;
    private String request;
    private String response;
    private Integer status;
    private Long costMs;
    private String errorMsg;
    private LocalDateTime createdAt;
}