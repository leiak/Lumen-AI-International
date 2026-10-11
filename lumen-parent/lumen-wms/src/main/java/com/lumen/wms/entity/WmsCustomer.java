package com.lumen.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 客户主数据.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_customer")
public class WmsCustomer extends BaseEntity {

    private String code;

    private String name;

    private String contact;

    private String phone;

    private String address;
}
