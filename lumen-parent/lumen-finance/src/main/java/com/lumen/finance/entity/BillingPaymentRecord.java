package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
@TableName("t_payment_record")
public class BillingPaymentRecord {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private String period;
    private String direction;
    private Long counterpartyId;
    private String counterpartyType;
    private String currency;
    private BigDecimal amount;
    private String sourceRef;
    private LocalDate paymentDate;
    private String source;
    private String remark;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    private Long createdBy;
}
