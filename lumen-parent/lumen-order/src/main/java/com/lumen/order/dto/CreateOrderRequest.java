package com.lumen.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理端建单请求 (Iteration 4.6).
 *
 * <p><b>只有管理端用这个 record</b>。门户端用 {@code com.lumen.customer.dto.PortalFreightOrderRequest}
 * (Task 7 新建) —— 字段几乎一样, 但**没有 customerId**: 门户的客户由
 * {@code com.lumen.customer.security.CustomerContext} 强制注入,
 * body 里压根不该有这个字段。共用一个 DTO 会逼出「@NotNull 但门户不传」或「不标但管理端能漏传」
 * 的二选一, 两个都是坑。
 *
 * <p>{@code cargo} / {@code serviceItems} <b>不标 {@code @NotEmpty}</b> —— 空集合由 service 抛
 * {@code OrderErrorCode.CARGO_EMPTY} / {@code SERVICE_ITEM_EMPTY}, 避免同一个失败有两条码。
 */
public record CreateOrderRequest(
        @NotNull Long customerId,
        @NotBlank String businessType,
        @NotBlank String tradeDirection,
        @NotBlank String incoterms,
        @NotBlank String serviceType,
        @Size(max = 255) String originAddress,
        @Size(max = 255) String destAddress,
        LocalDate cargoReadyDate,
        LocalDate etd,
        LocalDate eta,
        Integer containerQty,
        @Size(max = 16) String containerTypeCode,
        @Size(max = 500) String marksAndNos,
        @Size(max = 500) String cargoDescription,
        Boolean isOversized,
        @Size(max = 32) String temperatureRange,
        String settlementCurrency,
        @Size(max = 1000) String remark,
        List<CargoRequest> cargo,
        List<ServiceItemRequest> serviceItems
) {}
