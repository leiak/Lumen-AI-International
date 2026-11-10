package com.lumen.order.dto;

import java.math.BigDecimal;

/** 服务项 (费用预算) 请求行 (Iteration 4.6). 管理端与门户端共用。 */
public record ServiceItemRequest(
        String feeType,
        String feeName,
        String feeCategory,
        String calcBasis,
        Double qty,
        Double unitPrice,
        Double amount,
        String currencyCode,
        Long supplierId,
        Double costAmount,
        Boolean isMandatory,
        String remark
) {}
