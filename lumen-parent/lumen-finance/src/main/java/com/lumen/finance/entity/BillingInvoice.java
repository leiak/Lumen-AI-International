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
    private BigDecimal fxRateAtCreation; // 4.3.2 PR3 — submit 时由 ExchangeRateService.getRate 回填 (V30 schema)
    private String status;               // DRAFT / PENDING_L1 / L1_APPROVED / PUSHED / REJECTED
    private Long ruleId;
    private Integer ruleVersion;
    private Long l1ApproverId;
    private LocalDateTime l1ApprovedAt;
    private String l1Comment;
    // 4.3.2 PR3 — L2 终审元信息 (schema V30 已加列)
    private Long l2ApproverId;
    private LocalDateTime l2ApprovedAt;
    private String l2Comment;
    private String errorMsg;             // V28 added; 推送失败 / 处理异常留痕 (PUSHED 等状态用)
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
    private LocalDateTime submittedAt;
    @TableLogic @TableField(select = false, fill = FieldFill.INSERT) private Integer deleted;
    @Version @TableField(fill = FieldFill.INSERT) private Integer version;
}