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
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
}
