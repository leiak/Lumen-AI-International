package com.lumen.order.dto;

import java.math.BigDecimal;

/**
 * 货物明细请求行 (Iteration 4.6).
 *
 * <p>管理端 {@code CreateOrderRequest} 与门户端 {@code PortalFreightOrderRequest}
 * (Task 7 新建, {@code com.lumen.customer.dto}) 共用这一个 record。
 *
 * <p>字段级约束 (长度/正数) 由 jakarta bean validation 兜住 → HTTP 400 + PARAM_INVALID (4001);
 * 本 record 不做集合级校验 (「明细不能为空」是 service 的事, 抛 400_101)。
 */
public record CargoRequest(
        String cargoNameCn,
        String cargoNameEn,
        String hsCode,
        String pkgType,
        Double pkgQty,
        String unit,
        Double weightKg,
        Double netWeightKg,
        Double volumeCbm,
        Double lengthCm,
        Double widthCm,
        Double heightCm,
        Boolean isStackable,
        Double cargoValue,
        String currencyCode,
        String brandName,
        String model,
        Boolean isDangerous,
        String unNo,
        String dangerClass,
        String remark
) {}
