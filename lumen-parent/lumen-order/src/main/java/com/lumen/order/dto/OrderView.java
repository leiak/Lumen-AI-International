package com.lumen.order.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 委托单列表项 (Iteration 4.6).
 *
 * <p><b>不含 customerName</b> —— 照 {@code lumen-dispatch} 的既有做法
 * ({@code WmsDeliveryOrder} 只有 {@code customerId})。管理端前端用已有的
 * {@code services/wms/customer.ts} ({@code GET /api/v1/wms/customers}) 做 id→名称映射;
 * 门户端用已有的 {@code GET /api/customer/profile}。
 */
public record OrderView(
        Long id,
        String orderNo,
        Long customerId,
        String businessType,
        String tradeDirection,
        String incoterms,
        String serviceType,
        String originAddress,
        String destAddress,
        BigDecimal totalQty,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeCbm,
        String orderStatus,
        String settlementCurrency,
        LocalDate etd,
        LocalDate eta,
        LocalDateTime createdAt
) {}
