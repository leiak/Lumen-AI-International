package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}