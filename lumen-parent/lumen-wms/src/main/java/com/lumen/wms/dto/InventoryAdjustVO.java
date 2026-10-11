package com.lumen.wms.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存调整单视图对象（含其全部行项目）.
 */
@Data
public class InventoryAdjustVO {

    private Long id;
    private Long tenantId;
    private String no;
    private Long warehouseId;
    private String status;
    private String reason;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<InventoryAdjustLineVO> lines;
}
