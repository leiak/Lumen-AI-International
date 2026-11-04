package com.lumen.customer.security;

import com.lumen.customer.exception.CustomerException;

public final class CustomerContext {
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> CUSTOMER_ID = new ThreadLocal<>();

    private CustomerContext() {}

    public static void set(Long userId, Long customerId) {
        USER_ID.set(userId);
        CUSTOMER_ID.set(customerId);
    }

    public static Long getUserId() { return USER_ID.get(); }

    public static Long requireCustomerId() {
        Long id = CUSTOMER_ID.get();
        if (id == null) throw new CustomerException(401_002, "未激活客户身份");
        return id;
    }

    /**
     * 取当前登录用户 id (强制注入 — 不可为 null).
     *
     * <p>4.5C-2 需要它: 对账异议要落到具体的人身上 (推进调账单审批时当 operator),
     * 只有 customerId 记不了"谁提的异议"。此前门户只有读路径, 从没需要过 userId,
     * 所以只有 {@code getUserId()} 这个软版本。
     *
     * <p><b>为什么是 401_002 而不是 401_003</b>: 401_003 在 {@code PortalErrorCode}
     * 里注册的是"用户名或密码错误" —— 那是**登录失败**。而 userId 缺失是
     * "当前线程没激活门户身份" (与 {@code requireCustomerId} 的 401_002 同类:
     * {@code CustomerContextFilter} 一次性 set 了 userId 与 customerId,
     * 两者都来自同一个 token)。门户前端按 {@code code} 映射文案, 用 401_003 会让
     * "token 缺 userId" 显示成"用户名或密码错误" —— 误导商家去改密码。
     * 401_002 未在 {@code PortalErrorCode} 注册, 但 {@code CustomerExceptionHandler}
     * 的 {@code deriveHttpStatus} 会把它推成 401 ✓。
     *
     * @throws CustomerException 401_002 未激活门户身份
     */
    public static Long requireUserId() {
        Long id = USER_ID.get();
        if (id == null) throw new CustomerException(401_002, "未激活用户身份");
        return id;
    }

    public static void clear() {
        USER_ID.remove();
        CUSTOMER_ID.remove();
    }
}