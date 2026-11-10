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
    /**
     * 数量 —— 列 {@code qty DECIMAL(18,3)}。
     * 落库前必须 {@code setScale(3)}, 否则多出的位数被 MySQL **静默舍入** (不报错)。
     */
    private BigDecimal qty;
    /**
     * 单价 —— 列 {@code unit_price DECIMAL(18,4)}。
     * 落库前必须 {@code setScale(4)}, 否则多出的位数被 MySQL **静默舍入** (不报错)。
     */
    private BigDecimal unitPrice;
    /**
     * 金额 —— 列 {@code amount DECIMAL(18,2)}。
     * 落库前必须 {@code setScale(2)}, 否则多出的位数被 MySQL **静默舍入** (不报错)。
     */
    private BigDecimal amount;
    private String currencyCode;
    private Long supplierId;
    /**
     * 成本额 —— 列 {@code cost_amount DECIMAL(18,2)}。
     * 落库前必须 {@code setScale(2)}, 否则多出的位数被 MySQL **静默舍入** (不报错)。
     */
    private BigDecimal costAmount;
    /**
     * 已锁定金额 —— 列 {@code locked_amount DECIMAL(18,2)}。
     * 落库前必须 {@code setScale(2)}, 否则多出的位数被 MySQL **静默舍入** (不报错)。
     */
    private BigDecimal lockedAmount;
    private Integer isLocked;
    private Integer isMandatory;
    private String source;
    private String remark;
}
