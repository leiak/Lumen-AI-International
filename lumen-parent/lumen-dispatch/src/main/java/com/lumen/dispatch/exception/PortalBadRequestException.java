package com.lumen.dispatch.exception;

/**
 * Portal 端校验 / 状态错误 (Iteration 4.5B.2 T2.3).
 *
 * <p>用于 dispatch 服务的 createForCustomer / cancelForCustomer 抛错 —
 * portal controller (lumen-customer) 捕获后重新包装为 {@code CustomerException}
 * 做 HTTP 响应映射.
 *
 * <p>本类为 dispatch-owned — 复用 {@code PortalErrorCode} 的数值常量
 * (400_001 = ORDER_NOT_CANCELLABLE / 400_002 = ORDER_PICKUP_WINDOW_INVALID /
 * 409_001 = 同前), 但不 import {@code com.lumen.customer.error.PortalErrorCode}
 * (避免 dispatch → customer Maven 循环). code 在抛出处用 int 写死.
 *
 * <p>code 语义约定:
 * <ul>
 *   <li>400_001 — generic bad request (取件窗口终点非法 / 跨日 / …)</li>
 *   <li>400_002 — 取件窗口起点非法 (太近 / 太远)</li>
 *   <li>409_001 — 不能取消的状态 (PENDING_DISPATCH 之外)</li>
 * </ul>
 */
public class PortalBadRequestException extends RuntimeException {

    private final int code;

    public PortalBadRequestException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
