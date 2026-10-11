package com.lumen.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新库存调整单请求体.
 *
 * <p>同入库单：行项目为整量替换.
 */
@Data
public class UpdateInventoryAdjustRequest {

    @Size(max = 32)
    private String no;

    private Long warehouseId;

    @Size(max = 64)
    private String reason;

    @Size(max = 500)
    private String remark;

    private List<@Valid InventoryAdjustLineDto> lines;

    public boolean hasLines() {
        return lines != null;
    }

    public boolean hasNonEmptyLines() {
        return lines != null && !lines.isEmpty();
    }
}
