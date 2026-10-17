package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
@TableName("t_monthly_closing")
public class BillingMonthlyClosing {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private String period;
    private String status;
    private BigDecimal snapshotTotalInvoice;
    private BigDecimal snapshotTotalAccrual;
    private BigDecimal snapshotTotalAdjustment;
    private String snapshotCurrency;
    private LocalDateTime snapshotRecalculatedAt;
    private Long closedBy;
    private LocalDateTime closedAt;
    private String reopenReason;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
}
