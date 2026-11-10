package com.lumen.order.dto;

import java.math.BigDecimal;

public record ServiceItemView(
        Long id,
        String feeType,
        String feeName,
        String feeCategory,
        String calcBasis,
        BigDecimal qty,
        BigDecimal unitPrice,
        BigDecimal amount,
        String currencyCode,
        Long supplierId,
        BigDecimal costAmount,
        Integer isMandatory,
        String source,
        String remark
) {}
