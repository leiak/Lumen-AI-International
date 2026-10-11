package com.lumen.wms.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出库单视图对象.
 *
 * <p>{@code lines} 字段不会被 {@code toVO(...)} 自动填充 — 调用方需显式
 * 调用 {@code setLines(lineVOs(...))} 装配行项目.
 */
@Data
public class OutboundNoteVO {

    private Long id;
    private Long tenantId;
    private String no;
    private Long customerId;
    private Long warehouseId;
    private String status;
    private LocalDateTime requestedAt;
    private LocalDateTime shippedAt;
    private String carrier;
    private String trackingNo;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<OutboundLineVO> lines;
}
