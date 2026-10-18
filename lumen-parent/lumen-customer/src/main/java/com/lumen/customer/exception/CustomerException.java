package com.lumen.customer.exception;

/**
 * 客户门户异常 (Iteration 4.5A).
 *
 * <p>由 {@code CustomerContextFilter} 和 {@code CustomerContext.requireCustomerId()} 抛出:
 * <ul>
 *   <li>401_001 — 需要 portal token (aud=portal) 但 SecurityContext principal 不是 RbacUserDetails / 缺 Bearer 头</li>
 *   <li>401_002 — token 缺少 active_customer_id claim / 当前线程未激活客户身份</li>
 * </ul>
 *
 * <p>由 {@code com.lumen.customer.web.CustomerExceptionHandler} (后续 4.5A.2-T2.6/ControllerAdvice 任务) 翻译成 401 JSON 响应.
 *
 * <p>{@code int code} 字段供 Handler 写入 JSON body 的 {@code code} 字段.
 */
public class CustomerException extends RuntimeException {
    private final int code;

    public CustomerException(int code, String message) { super(message); this.code = code; }
    public int getCode() { return code; }
}