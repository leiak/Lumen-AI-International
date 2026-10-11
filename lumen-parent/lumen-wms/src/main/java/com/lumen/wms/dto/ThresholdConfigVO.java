package com.lumen.wms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 阈值配置视图对象.
 */
@Data
public class ThresholdConfigVO {

    private Long id;
    private Long tenantId;
    private BigDecimal inbAmountThreshold;
    private BigDecimal inbQtyThreshold;
    private BigDecimal outAmountThreshold;
    private BigDecimal outQtyThreshold;
    private BigDecimal adjAmountThreshold;
    private BigDecimal adjQtyThreshold;
    private Integer approvalRequiredWhenExceed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
