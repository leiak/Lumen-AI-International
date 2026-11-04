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
    PORTAL_USER_NOT_FOUND(404_001, 404, "用户不存在"),

    // 4.5B.2 新增 — 客户下单 / 取消错误码

    // 409 段 — 状态冲突
    ORDER_NOT_CANCELLABLE(409_001, 409, "订单当前状态不可取消 (仅 PENDING_DISPATCH 可取消)"),

    // 400 段 — 入参校验失败 (取件时间窗)
    ORDER_PICKUP_WINDOW_INVALID(400_001, 400, "取件时间窗口无效"),
    ORDER_PICKUP_WINDOW_TOO_SHORT(400_002, 400, "取件时间窗口需 >= 2 小时后, <= 30 天内"),

    // 4.5C-2 新增 — 商家门户对账 / 发票 / 付款 / 调账单

    // 404 段 — 资源不存在或不属于该商家。四个码刻意不区分这两种情形:
    // 商家拿到 404 就该认为"那不是我的东西", 继续区分等于泄露别人单据是否存在。
    RECON_NOT_FOUND      (404_002, 404, "对账单不存在"),
    INVOICE_NOT_FOUND    (404_003, 404, "发票不存在"),
    PAYMENT_NOT_FOUND    (404_004, 404, "付款记录不存在"),
    ADJUSTMENT_NOT_FOUND (404_005, 404, "调账单不存在"),

    // 409 段 — 状态冲突
    RECON_NOT_DISPUTABLE (409_002, 409, "该期对账单当前不可提异议 (已异议过 / 期间已闭期 / 无关联调账单)"),

    // 400 段 — 异议入参形状校验
    DISPUTE_REASON_REQUIRED (400_003, 400, "请填写异议理由"),
    DISPUTE_AMOUNT_INVALID  (400_004, 400, "主张金额超出可表示范围 (最多 14 位整数 + 4 位小数)"),

    // 400 段 — finance 侧通用参数错误 (如 period 格式非法)
    PORTAL_PARAM_INVALID (400_005, 400, "请求参数非法"),

    // 500 段 — 导出失败 (服务端问题, 不是商家请求的问题)
    EXPORT_FAILED        (500_001, 500, "导出失败, 请稍后重试");

    private final int code;
    private final String message;
    private final int httpStatus;

    PortalErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}