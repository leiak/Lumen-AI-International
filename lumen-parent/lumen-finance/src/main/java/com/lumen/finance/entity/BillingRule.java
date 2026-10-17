package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 计费规则头 (spec §3.1).
 *
 * <p>tenant_id + business_type + direction + counterparty_type + effective_from 联合 UNIQUE 防重复.
 */
@Data
@TableName("t_billing_rule")
public class BillingRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String businessType;       // LAST_MILE / WAREHOUSE_IN / WAREHOUSE_OUT
    private String direction;          // RECEIVABLE / PAYABLE
    private String counterpartyType;   // CUSTOMER / CARRIER / WAREHOUSE
    private String currency;           // CNY (4.3.1 only)
    private Integer priority;          // 数字越小越优先
    private String status;             // DRAFT / ACTIVE / DISABLED / INVALID
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String remark;
    @TableField(fill = FieldFill.INSERT) private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private Long updatedBy;
    @TableLogic @TableField(select = false, fill = FieldFill.INSERT) private Integer deleted;
    @Version @TableField(fill = FieldFill.INSERT) private Integer version;
}