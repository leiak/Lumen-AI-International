package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 业务事件溯源 - 幂等 (spec §3.3).
 *
 * <p>UNIQUE (tenant_id, source_event_id, deleted) 防重复.
 */
@Data
@TableName("t_billing_event_log")
public class BillingEventLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String sourceEventId;
    private String businessType;
    private Long businessId;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime receivedAt;
    private LocalDateTime processedAt;
    private String processStatus;       // RECEIVED / SUCCESS / FAILED / NO_RULE
    private String errorMsg;
    @TableLogic @TableField(select = false, fill = FieldFill.INSERT) private Integer deleted;
}