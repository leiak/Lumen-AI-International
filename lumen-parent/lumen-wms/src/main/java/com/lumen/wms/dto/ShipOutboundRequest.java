package com.lumen.wms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 出库发货请求体（T4.2 状态机）.
 *
 * <p>由 {@code ship()} 接收并写入主单 {@code carrier} / {@code trackingNo}，
 * 状态从 REVIEW 翻转为 SHIPPED.
 */
@Data
public class ShipOutboundRequest {

    @NotBlank
    @Size(max = 64)
    private String carrier;

    @NotBlank
    @Size(max = 64)
    private String trackingNo;
}
