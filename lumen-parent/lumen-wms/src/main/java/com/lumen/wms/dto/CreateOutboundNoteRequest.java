package com.lumen.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 新增出库单请求体.
 *
 * <p>创建时同时下发全部行项目（行级在主单创建时一次性插入）.
 */
@Data
public class CreateOutboundNoteRequest {

    @NotBlank
    @Size(max = 32)
    private String no;

    @NotNull
    private Long customerId;

    @NotNull
    private Long warehouseId;

    private LocalDateTime requestedAt;

    @Size(max = 64)
    private String carrier;

    @Size(max = 64)
    private String trackingNo;

    @Size(max = 500)
    private String remark;

    @NotEmpty
    private List<@Valid OutboundLineDto> lines;
}
