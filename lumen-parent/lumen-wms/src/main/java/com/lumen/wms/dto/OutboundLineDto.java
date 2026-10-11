package com.lumen.wms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 出库单行项目 DTO.
 *
 * <p>同时用于新建时的内嵌列表项、追加单行(AddOutboundLineRequest)以及更新行的请求体.
 * 与入库单不同，出库单每行必须明确指定 {@code sourceLocationId}（拣货位）.
 */
@Data
public class OutboundLineDto {

    @NotNull
    private Long itemId;

    @NotNull
    @DecimalMin(value = "0.0001", inclusive = true)
    private BigDecimal qty;

    @NotNull
    private Long sourceLocationId;
}
