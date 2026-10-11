package com.lumen.wms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 库存调整单行 DTO.
 *
 * <p>{@code deltaQty} 可正可负；正值表示盘盈入库，负值表示盘亏出库.
 */
@Data
public class InventoryAdjustLineDto {

    @NotNull
    private Long itemId;

    @NotNull
    private Long locationId;

    /**
     * 调整量；绝对值需 >= 0.0001，因此允许正负（负数即盘亏）.
     */
    @NotNull
    @DecimalMin(value = "-99999999999999.9999", inclusive = true)
    private BigDecimal deltaQty;

    @Size(max = 255)
    private String reasonDetail;
}
