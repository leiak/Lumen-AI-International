package com.lumen.order.dto;

import java.math.BigDecimal;

public record CargoView(
        Long id,
        String cargoNameCn,
        String cargoNameEn,
        String hsCode,
        String pkgType,
        BigDecimal pkgQty,
        String unit,
        BigDecimal weightKg,
        BigDecimal netWeightKg,
        BigDecimal volumeCbm,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        Integer isStackable,
        BigDecimal cargoValue,
        String currencyCode,
        String brandName,
        String model,
        Integer isDangerous,
        String unNo,
        String dangerClass,
        String remark
) {}
