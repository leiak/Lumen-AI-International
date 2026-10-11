package com.lumen.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新增入库单请求体.
 *
 * <p>创建时同时下发全部行项目（行级在主单创建时一次性插入）.
 */
@Data
public class CreateInboundNoteRequest {

    @NotBlank
    @Size(max = 32)
    private String no;

    @NotNull
    private Long customerId;

    @NotNull
    private Long warehouseId;

    @Size(max = 500)
    private String remark;

    @NotEmpty
    private List<@Valid InboundLineDto> lines;
}
