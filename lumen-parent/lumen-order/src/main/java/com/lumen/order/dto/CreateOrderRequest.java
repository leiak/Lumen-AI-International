package com.lumen.order.dto;

import jakarta.validation.Valid;
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
 * 但两个集合组件<b>标了 {@code @Valid}</b> —— 不标的话嵌套 record 上的 jakarta 约束
 * ({@code CargoRequest} / {@code ServiceItemRequest} 的长度与正数) 不会被级联校验,
 * 超长字符串会一路走到 MySQL 报 1406 → 500 而不是 400。
 *
 * <p><b>长度约束逐列对齐 {@code t_order} 的真实列宽</b> (V42 迁移):
 * {@code business_type → VARCHAR(32)}、{@code trade_direction / incoterms / service_type → VARCHAR(16)}、
 * {@code origin_address / dest_address → VARCHAR(255)}、{@code container_type_code → VARCHAR(16)}、
 * {@code marks_and_nos / cargo_description → VARCHAR(500)}、{@code temperature_range → VARCHAR(32)}、
 * {@code remark → VARCHAR(1000)}、{@code settlement_currency → CHAR(3)}。
 * 共 12 个定长/限长字段, 全部有 {@code @Size} —— 少一个就等于超长时撞 MySQL 1406 → 500 而不是 400。
 */
public record CreateOrderRequest(
        @NotNull Long customerId,
        @NotBlank @Size(max = 32) String businessType,
        @NotBlank @Size(max = 16) String tradeDirection,
        @NotBlank @Size(max = 16) String incoterms,
        @NotBlank @Size(max = 16) String serviceType,
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
        @Size(max = 3) String settlementCurrency,
        @Size(max = 1000) String remark,
        @Valid List<CargoRequest> cargo,
        @Valid List<ServiceItemRequest> serviceItems
) {}
