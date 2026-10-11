package com.lumen.wms.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仓库视图对象.
 */
@Data
public class WarehouseVO {

    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String type;
    private String status;
    private String address;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}