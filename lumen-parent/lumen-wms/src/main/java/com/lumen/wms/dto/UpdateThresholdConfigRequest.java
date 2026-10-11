package com.lumen.wms.dto;

import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新阈值配置请求体.
 *
 * <p>主键 / 租户不可由调用方传入 — 服务层按 tenant 上下文定位唯一行.
 * 阈值为非负数.
 */
@Data
public class UpdateThresholdConfigRequest {

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal inbAmountThreshold;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal inbQtyThreshold;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal outAmountThreshold;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal outQtyThreshold;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal adjAmountThreshold;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal adjQtyThreshold;

    /**
     * 可选：1=超过阈值需审批，0=不启用审批闸门.
     */
    private Integer approvalRequiredWhenExceed;
}
