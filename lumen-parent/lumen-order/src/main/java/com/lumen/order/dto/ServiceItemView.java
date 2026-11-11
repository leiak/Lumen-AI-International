package com.lumen.order.dto;

import java.math.BigDecimal;

/**
 * 服务项 (费用预算) 视图行 (Iteration 4.6).
 *
 * <p>{@code supplierId} (我方供应商) 与 {@code costAmount} (我方成本额) 是
 * <b>内部字段</b> —— 管理端要看到, 客户门户不能看到。
 */
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
) {

    /**
     * 返回一份把内部成本字段 ({@code supplierId} / {@code costAmount}) 置 null 的副本。
     * 门户端给客户看的服务项**不应该**带我方成本 —— 见 PortalFreightOrderController#detail。
     */
    public ServiceItemView withoutInternalCost() {
        return new ServiceItemView(id, feeType, feeName, feeCategory, calcBasis,
                qty, unitPrice, amount, currencyCode, null, null, isMandatory, source, remark);
    }
}
