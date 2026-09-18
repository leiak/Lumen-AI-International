package com.lumen.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification_send_log")
public class NotificationSendLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String templateCode;
    private String channel;
    private String receiver;
    private String payload;
    private Integer status;
    private String errorMsg;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
