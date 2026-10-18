package com.lumen.customer.error;

import com.lumen.common.error.ErrorCode;
import lombok.Getter;

/**
 * 客户门户 (B2B) 错误码 (Iteration 4.5A).
 *
 * <p>由 {@code PortalAuthService} 抛出, 通过 {@code AuthException(code, msg)} 包装,
 * 后续 {@code CustomerExceptionHandler} (T2.6) 翻译成 401/403/404 JSON 响应.
 */
@Getter
public enum PortalErrorCode implements ErrorCode {

    // 401 段 — 认证失败
    PORTAL_AUTH_INVALID(401_003, 401, "用户名或密码错误"),

    // 403 段 — 授权失败
    PORTAL_USER_NOT_LINKED(403_001, 403, "无权访问该客户"),
    PORTAL_NO_CUSTOMER_ROLE(403_002, 403, "该账号未开通客户门户"),
    PORTAL_NO_CUSTOMER_LINK(403_003, 403, "账号未绑定任何客户"),

    // 404 段 — 资源不存在
    PORTAL_USER_NOT_FOUND(404_001, 404, "用户不存在");

    private final int code;
    private final String message;
    private final int httpStatus;

    PortalErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}