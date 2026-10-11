package com.lumen.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新出库单请求体.
 *
 * <p>更新为整量替换行项目（先全删旧行再写入新行）.
 * 主键 / 租户 / 创建时间 / 状态等不可变字段由服务层忽略.
 * 与入库单不同，主单的 {@code no / customerId / warehouseId} 不允许通过本接口修改.
 */
@Data
public class UpdateOutboundNoteRequest {

    @Size(max = 64)
    private String carrier;

    @Size(max = 64)
    private String trackingNo;

    @Size(max = 500)
    private String remark;

    /**
     * 为 null 表示不修改行；非空（含空列表）则整量替换.
     */
    private List<@Valid OutboundLineDto> lines;

    public boolean hasLines() {
        return lines != null;
    }

    public boolean hasNonEmptyLines() {
        return lines != null && !lines.isEmpty();
    }
}
