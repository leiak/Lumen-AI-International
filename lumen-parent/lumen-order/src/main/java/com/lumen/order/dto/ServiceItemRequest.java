package com.lumen.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 服务项 (费用预算) 请求行 (Iteration 4.6). 管理端与门户端共用。
 *
 * <p><b>约束宽度逐列对齐 {@code t_order_service_item} 的真实列宽</b> (V42 迁移):
 * {@code fee_type → VARCHAR(32) NOT NULL}、{@code fee_name → VARCHAR(128) NOT NULL}、
 * {@code fee_category → VARCHAR(32)}、{@code calc_basis → VARCHAR(16) NOT NULL}、
 * {@code currency_code → CHAR(3)}、{@code remark → VARCHAR(500)};
 * 数量/单价/金额三列是 {@code DECIMAL}, 不允许负数。
 *
 * <p>三列 {@code NOT NULL} 的字段 ({@code feeType} / {@code feeName} / {@code calcBasis}) 标
 * {@code @NotBlank} —— 少传会被 jakarta bean validation 挡在 controller 口 → HTTP 400 + PARAM_INVALID (4001),
 * 而不是落一条 {@code NULL} 明细进库。
 */
public record ServiceItemRequest(
        @NotBlank @Size(max = 32) String feeType,
        @NotBlank @Size(max = 128) String feeName,
        @Size(max = 32) String feeCategory,
        @NotBlank @Size(max = 16) String calcBasis,
        @PositiveOrZero Double qty,
        @PositiveOrZero Double unitPrice,
        @PositiveOrZero Double amount,
        @Size(max = 3) String currencyCode,
        Long supplierId,
        Double costAmount,
        Boolean isMandatory,
        @Size(max = 500) String remark
) {}
