package com.lumen.order.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 委托单详情 (Iteration 4.6).
 *
 * <p>{@code allowedNextStates} = {@code StateMachineEngine.allowedNext("ORDER", 当前态)},
 * 前端据此渲染可执行动作按钮 —— 这是本轮唯一一处「表驱动状态机反向驱动 UI」的地方。
 */
public record OrderDetailView(
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
        LocalDateTime createdAt,
        LocalDate cargoReadyDate,
        Integer containerQty,
        String containerTypeCode,
        String marksAndNos,
        String cargoDescription,
        Integer isDangerous,
        Integer isOversized,
        String temperatureRange,
        String remark,
        String cancelReason,
        String closeReason,
        String voidReason,
        LocalDateTime submittedAt,
        LocalDateTime confirmedAt,
        LocalDateTime deliveredAt,
        LocalDateTime settledAt,
        LocalDateTime closedAt,
        Long createdBy,
        Long updatedBy,
        List<CargoView> cargo,
        List<ServiceItemView> serviceItems,
        List<String> allowedNextStates
) {}
