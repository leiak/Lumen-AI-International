package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 暂估单 (spec §3.4).
 *
 * <p>UNIQUE (tenant_id, source_event_id, business_type, deleted) 防重复.
 */
@Data
@TableName("t_billing_accrual")
public class BillingAccrual {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String sourceEventId;
    private String businessType;
    private Long businessId;
    private Long ruleId;
    private String direction;            // RECEIVABLE / PAYABLE
    private Long counterpartyId;
    private BigDecimal grossAmount;
    private BigDecimal taxAmount;
    private BigDecimal netAmount;
    private String currency;
    private String status;               // OPEN / LOCKED / CONVERTED / RELEASED / OPEN_FAILED
    private String payloadJson;
    private String errorMsg;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
    private LocalDateTime lockedAt;
    @TableLogic @TableField(select = false, fill = FieldFill.INSERT) private Integer deleted;
    @Version @TableField(fill = FieldFill.INSERT) private Integer version;
}