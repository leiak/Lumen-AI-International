package com.lumen.wms.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户视图对象.
 */
@Data
public class CustomerVO {

    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String contact;
    private String phone;
    private String address;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}