package com.lumen.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新入库单请求体.
 *
 * <p>更新为整量替换行项目（先全删旧行再写入新行）.
 * 主键 / 租户 / 创建时间等不可变字段由服务层忽略.
 */
@Data
public class UpdateInboundNoteRequest {

    @Size(max = 32)
    private String no;

    private Long customerId;

    private Long warehouseId;

    @Size(max = 500)
    private String remark;

    /**
     * 为 null 表示不修改行；非空（含空列表）则整量替换.
     */
    private List<@Valid InboundLineDto> lines;

    public boolean hasLines() {
        return lines != null;
    }

    public boolean hasNonEmptyLines() {
        return lines != null && !lines.isEmpty();
    }
}
