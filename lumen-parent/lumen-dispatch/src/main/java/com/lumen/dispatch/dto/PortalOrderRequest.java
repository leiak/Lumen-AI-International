package com.lumen.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户下单 service 层 DTO (Iteration 4.5B.2 T2.3).
 *
 * <p>字段与 customer-portal 的 PortalCreateOrderRequest 一致, controller 层做映射.
 * <p>dispatch 不依赖 lumen-customer, 故独立定义 (避免反向依赖 / Maven 循环).
 * <p>tenantId / customerId 不在 DTO, 由 service 调用方强制注入 — 防越权.
 *
 * <p>address 字段 (shipper / consignee) 当前 {@code wms_delivery_order} 表没有专门的列
 * (4.5A.x 调研确认, 仅 {@code wms_delivery_stop} 有), 故 createForCustomer 走 sourceNo /
 * remark 占位 — 4.5B 后续 PR 把这两个字段写进 {@code wms_delivery_stop}.
 */
public record PortalOrderRequest(
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
