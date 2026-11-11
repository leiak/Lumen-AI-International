package com.lumen.customer.dto;

/**
 * 客户取消货运委托单请求 (Iteration 4.6, Task 7).
 *
 * <p><b>为什么不标 jakarta 约束</b> —— 原因的非空 / 长度统一由
 * {@code OrderServiceImpl.requireReason} 判, 落 order 域自己的码位
 * (400_103 空 / 400_108 短于 5 字 / 400_107 超过 255 字)。
 * 若这里再标一个 {@code @NotBlank}, 同一个失败会出两条码
 * (jakarta 的 4001 与 order 域的 400_103), 客户端不好判 —— 本仓一贯
 * 「一个失败一条码」, 所以这里只做**类型化** (替掉裸 Map), 不做校验。
 */
public record PortalCancelFreightOrderRequest(String reason) {}
