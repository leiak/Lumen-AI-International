package com.lumen.wms.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库单视图对象（含其全部行项目）.
 */
@Data
public class InboundNoteVO {

    private Long id;
    private Long tenantId;
    private String no;
    private Long customerId;
    private Long warehouseId;
    private String status;
    private LocalDateTime arrivedAt;
    private LocalDateTime completedAt;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<InboundLineVO> lines;
}
