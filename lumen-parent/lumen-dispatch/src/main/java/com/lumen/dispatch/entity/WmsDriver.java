package com.lumen.dispatch.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 司机主数据 (Driver master).
 *   status ACTIVE/SUSPENDED (DB-level constraint)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_driver")
public class WmsDriver extends BaseEntity {

    private String name;

    private String phone;

    private String licenseNo;

    private String status;

    private String remark;
}