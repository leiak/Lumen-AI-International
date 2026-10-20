package com.lumen.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户下单请求 (Iteration 4.5B.2).
 * <p>customerId / tenantId 不在 DTO, 由 server 强制注入 (从 CustomerContext).
 *
 * <p>pickup window 校验在 service impl 兜底 (§7.2): 同日 + start >= now+2h + start <= now+30d +
 * end > start + end <= start+8h.
 *
 * <p>{@code plannedPickupWindowStart/End} 是 {@link LocalDateTime} — 前端必须发无时区的
 * 本地 ISO 串 ({@code "2026-10-20T14:00:00"}); 带 {@code Z} / 偏移量的串
 * ({@code dayjs().toISOString()}) Jackson 反序列化会失败.
 */
public record PortalCreateOrderRequest(
    @NotNull Long warehouseId,

    @NotBlank @Size(max = 255) String shipperAddress,

    @NotBlank @Size(max = 255) String consigneeAddress,

    @NotNull @Positive BigDecimal totalWeightKg,

    @NotNull @Positive BigDecimal totalVolumeM3,

    @NotNull @Positive Integer stopCount,

    @NotNull LocalDateTime plannedPickupWindowStart,

    LocalDateTime plannedPickupWindowEnd,  // 可空; 空 = start + 4h (service 兜底)

    @Size(max = 500) String remark
) {}
