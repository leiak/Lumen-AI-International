package com.lumen.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 委托单货物明细 {@code t_order_cargo} (Iteration 4.6).
 *
 * <p>本表没有 {@code created_by / updated_by} 两列, 因此实体也不声明这两个字段
 * (审计字段统一走 {@code BaseEntity} 的 {@code createdAt / updatedAt})。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order_cargo")
public class OrderCargo extends BaseEntity {

    private Long orderId;
    private String cargoNameCn;
    private String cargoNameEn;
    private String hsCode;
    private String pkgType;
    private BigDecimal pkgQty;
    private String unit;
    private BigDecimal weightKg;
    private BigDecimal netWeightKg;
    private BigDecimal volumeCbm;
    private BigDecimal lengthCm;
    private BigDecimal widthCm;
    private BigDecimal heightCm;
    private Integer isStackable;
    private BigDecimal cargoValue;
    private String currencyCode;
    private String brandName;
    private String model;
    private Integer isDangerous;
    private String unNo;
    private String dangerClass;
    private String remark;
}
