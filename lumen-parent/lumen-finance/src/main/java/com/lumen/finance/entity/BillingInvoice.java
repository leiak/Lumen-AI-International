package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 应收/应付正式单据 (spec §3.5).
 *
 * <p>UNIQUE invoice_no + UNIQUE source_accrual_id 防重复.
 */
@Data
@TableName("t_billing_invoice")
public class BillingInvoice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String invoiceNo;
    private Long tenantId;
    private String direction;
    private Long counterpartyId;
    private String counterpartyType;
    private String businessType;
    private Long sourceAccrualId;
    private Integer periodYear;          // 4.3.3 用, 4.3.1 可空
    private Integer periodMonth;
    private BigDecimal grossAmount;
    private BigDecimal taxAmount;
    private BigDecimal netAmount;
    private String currency;
    private String status;               // DRAFT / PENDING_L1 / L1_APPROVED / PUSHED / REJECTED
    private Long ruleId;
    private Integer ruleVersion;
    private Long l1ApproverId;
    private LocalDateTime l1ApprovedAt;
    private String l1Comment;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
    private LocalDateTime submittedAt;
    @TableLogic @TableField(select = false, fill = FieldFill.INSERT) private Integer deleted;
    @Version @TableField(fill = FieldFill.INSERT) private Integer version;
}