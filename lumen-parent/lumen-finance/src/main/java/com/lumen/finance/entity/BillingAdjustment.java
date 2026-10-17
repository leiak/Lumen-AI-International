package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_billing_adjustment")
public class BillingAdjustment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String adjustmentNo;
    private Long sourceInvoiceId;
    private String adjustmentType; // CREDIT / DEBIT
    private BigDecimal amount;
    private String currency;
    private BigDecimal fxRateAtCreation;
    private BigDecimal taxRate;
    private BigDecimal grossAmount;
    private BigDecimal taxAmount;
    private BigDecimal netAmount;
    private String reason;
    private String status;
    private Long l1ApproverId;
    private LocalDateTime l1ApprovedAt;
    private String l1Comment;
    private Long l2ApproverId;
    private LocalDateTime l2ApprovedAt;
    private String l2Comment;
    private String errorMsg;
    // 4.3.2 PR4 — createdAt/updatedAt 由 MyMetaObjectHandler 自动填 (与 BillingInvoice 对齐)
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
    // 注意: V29 schema 没有 version / deleted 列 (调账单支持硬删除或 append-only 审计),
    // 故意不加 @Version / @TableLogic, 与 t_billing_invoice 不同.
}