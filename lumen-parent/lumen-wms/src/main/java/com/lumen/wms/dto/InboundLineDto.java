package com.lumen.wms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 入库单行项目 DTO.
 *
 * <p>同时用于新建时的内嵌列表项、追加单行(AddInboundLineRequest)以及更新行的请求体.
 */
@Data
public class InboundLineDto {

    @NotNull
    private Long itemId;

    @NotNull
    @DecimalMin(value = "0.0001", inclusive = true)
    private BigDecimal qty;

    private Long putawayLocationId;
}
