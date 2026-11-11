package com.lumen.order.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 货物明细请求行 (Iteration 4.6).
 *
 * <p>管理端 {@code CreateOrderRequest} 与门户端 {@code PortalFreightOrderRequest}
 * (Task 7 新建, {@code com.lumen.customer.dto}) 共用这一个 record。
 *
 * <p>字段级约束 (长度/正数) 由 jakarta bean validation 兜住 → HTTP 400 + PARAM_INVALID (4001);
 * 本 record 不做集合级校验 (「明细不能为空」是 service 的事, 抛 400_101)。
 *
 * <p><b>约束宽度逐列对齐 {@code t_order_cargo} 的真实列宽</b> (V42 迁移) —— 宽度比列宽小的约束
 * 会把合法数据挡在门外, 宽度比列宽大的约束则形同虚设 (MySQL 直接截断或报 1406)。
 * 对应关系: {@code cargo_name_cn/cargo_name_en/brand_name/model → VARCHAR(128)},
 * {@code hs_code/un_no/danger_class → VARCHAR(16)}, {@code pkg_type → VARCHAR(32)},
 * {@code unit → VARCHAR(16)}, {@code currency_code → CHAR(3)}, {@code remark → VARCHAR(500)};
 * {@code pkg_qty} ≥ 0 (传 0 或不传 = 件数未知, 散货拼箱 LCL 常见), 与 DDL 的
 * {@code NOT NULL DEFAULT 0} 一致; 其余数量/尺寸/金额列 {@code DECIMAL} 不允许负数。
 *
 * <p><b>数量列的宽度由 service 兜</b> —— DTO 只管「非负」, 不管「装不装得下」:
 * {@code @PositiveOrZero} 放行 {@code 1e999} (Jackson 解析成 {@code Double.POSITIVE_INFINITY})
 * 与 {@code 1e15} (超出 {@code DECIMAL(18,3)}), 这两种都得在 service 里转成
 * {@code NUMERIC_OUT_OF_RANGE} (400_105), 否则会变成 500。
 */
public record CargoRequest(
        @Size(max = 128) String cargoNameCn,
        @Size(max = 128) String cargoNameEn,
        @Size(max = 16) String hsCode,
        @Size(max = 32) String pkgType,
        @PositiveOrZero Double pkgQty,
        @Size(max = 16) String unit,
        @PositiveOrZero Double weightKg,
        @PositiveOrZero Double netWeightKg,
        @PositiveOrZero Double volumeCbm,
        @PositiveOrZero Double lengthCm,
        @PositiveOrZero Double widthCm,
        @PositiveOrZero Double heightCm,
        Boolean isStackable,
        @PositiveOrZero Double cargoValue,
        @Size(max = 3) String currencyCode,
        @Size(max = 128) String brandName,
        @Size(max = 128) String model,
        Boolean isDangerous,
        @Size(max = 16) String unNo,
        @Size(max = 16) String dangerClass,
        @Size(max = 500) String remark
) {}
