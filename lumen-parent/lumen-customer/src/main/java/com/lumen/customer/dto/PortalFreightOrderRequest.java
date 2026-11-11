package com.lumen.customer.dto;

import com.lumen.order.dto.CargoRequest;
import com.lumen.order.dto.ServiceItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 门户端建委托单请求 (Iteration 4.6).
 *
 * <p>字段与 {@code com.lumen.order.dto.CreateOrderRequest} 完全一致,
 * <b>只是没有 {@code customerId}</b> —— 客户由 {@code PortalIdentity.requireCounterpartyId()}
 * 强制注入, body 里不该有这个字段。共用一个 DTO 会逼出「@NotNull 但门户不传」
 * 或「不标但管理端能漏传」的二选一, 两个都是坑。
 *
 * <p>{@code cargo} / {@code serviceItems} <b>不标 {@code @NotEmpty}</b> —— 空集合由
 * {@code OrderService} 抛 400_101 / 400_102, 避免同一个失败有两条码。
 *
 * <p><b>但集合组件上必须标 {@code @Valid}</b> —— {@code CargoRequest} /
 * {@code ServiceItemRequest} 自带 jakarta 约束 ({@code @Size} / {@code @PositiveOrZero} /
 * {@code @NotBlank}), 而嵌套对象的约束要生效, 父 record 的集合组件上必须有 {@code @Valid}
 * (Spring 的级联校验规则)。漏了 {@code @Valid} → 门户端这些字段的约束<b>一条都不会触发</b>
 * → 门户提交超长 hsCode / 负数量直接落库 (MySQL 1406 或负数入库), 而不是 400。
 * 管理端 {@code CreateOrderRequest} 的同名组件就是带 {@code @Valid} 的 —— <b>两边同口径</b>,
 * 不能一个级联一个不级联。
 *
 * <p><b>长度约束逐列对齐 {@code t_order} 的真实列宽</b> (V42 迁移) —— 与管理端
 * {@code CreateOrderRequest} <b>完全同口径</b>:
 * {@code business_type → VARCHAR(32)}、{@code trade_direction / incoterms / service_type → VARCHAR(16)}、
 * {@code origin_address / dest_address → VARCHAR(255)}、{@code container_type_code → VARCHAR(16)}、
 * {@code marks_and_nos / cargo_description → VARCHAR(500)}、{@code temperature_range → VARCHAR(32)}、
 * {@code remark → VARCHAR(1000)}、{@code settlement_currency → CHAR(3)}。
 * 少标一个 {@code @Size} 就等于超长时撞 MySQL 1406 → 500 而不是 400。
 */
public record PortalFreightOrderRequest(
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
