package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
@TableName("t_reconciliation")
public class BillingReconciliation {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private String period;
    private String direction;
    private Long counterpartyId;
    private String counterpartyType;
    private String currency;
    private BigDecimal expectedAmount;
    private BigDecimal actualAmount;
    private BigDecimal varianceAmount;
    private Long adjustmentId;
    private String reconciliationStatus;
    private String remark;
    // 4.5C-2 — 商家异议三列 (V39)。disputeAt 非空即代表该期次已被异议过,
    // 兼作幂等标记。disputeAmount 是商家主张, 绝不覆盖 varianceAmount
    // (那是财务算出来的账务金额, 两者语义不同, 不能互相推导)。
    private BigDecimal disputeAmount;
    private String disputeReason;
    private LocalDateTime disputeAt;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
}
