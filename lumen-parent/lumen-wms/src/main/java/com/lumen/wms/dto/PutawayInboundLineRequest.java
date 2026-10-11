package com.lumen.wms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 上架请求体（T3.4 状态机）.
 *
 * <p>单笔上架增量 + 目标库位 — 由服务层叠加到行项目 {@code putawayQty}，
 * 同时调用 {@code WmsInventoryService.addQty} 增加库存余额.
 */
@Data
public class PutawayInboundLineRequest {

    @NotNull
    @DecimalMin(value = "0.0001", inclusive = true, message = "qty 必须 > 0")
    private BigDecimal qty;

    @NotNull
    private Long locationId;
}