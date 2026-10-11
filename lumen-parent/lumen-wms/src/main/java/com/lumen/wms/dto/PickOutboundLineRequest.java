package com.lumen.wms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 出库行拣货请求体（T4.2 状态机）.
 *
 * <p>单笔拣货增量 — 由服务层叠加到行项目 {@code pickedQty}，并触发库存
 * {@code locked_qty} 的对应释放.
 */
@Data
public class PickOutboundLineRequest {

    @NotNull
    @DecimalMin(value = "0.0001", inclusive = true, message = "qty 必须 > 0")
    private BigDecimal qty;
}
