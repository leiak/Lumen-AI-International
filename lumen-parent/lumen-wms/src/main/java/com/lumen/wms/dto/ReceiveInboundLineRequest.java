package com.lumen.wms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 收货请求体（T3.4 状态机）.
 *
 * <p>单笔收货增量 — 由服务层叠加到行项目 {@code receivedQty}.
 */
@Data
public class ReceiveInboundLineRequest {

    @NotNull
    @DecimalMin(value = "0.0001", inclusive = true, message = "qty 必须 > 0")
    private BigDecimal qty;
}