package com.lumen.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 委托单服务项与费用预算 {@code t_order_service_item} (Iteration 4.6).
 *
 * <p>本表没有 {@code created_by / updated_by} 两列, 因此实体也不声明这两个字段
 * (审计字段统一走 {@code BaseEntity} 的 {@code createdAt / updatedAt})。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order_service_item")
public class OrderServiceItem extends BaseEntity {

    private Long orderId;
    private String feeType;
    private String feeName;
    private String feeCategory;
    private String calcBasis;
    private BigDecimal qty;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String currencyCode;
    private Long supplierId;
    private BigDecimal costAmount;
    private BigDecimal lockedAmount;
    private Integer isLocked;
    private Integer isMandatory;
    private String source;
    private String remark;
}
