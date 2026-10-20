package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 客户计费覆盖 (4.5B.1 新增).
 * <p>租户对单个客户的特例计费 — 高优先级覆盖 t_billing_rule 匹配结果.
 * 生效区间 [effectiveFrom, effectiveTo] 闭区间, asOf 命中即视为有效.
 * currency CNY (4.3.1 only).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_customer_rate_override")
public class CustomerRateOverride extends BaseEntity {

    private Long tenantId;

    private Long customerId;

    private String businessType;

    private BigDecimal baseAmount;

    private String currency;

    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    private String remark;
}
