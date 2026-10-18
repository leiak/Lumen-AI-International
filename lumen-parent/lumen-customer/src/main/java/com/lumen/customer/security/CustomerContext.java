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

    public static void clear() {
        USER_ID.remove();
        CUSTOMER_ID.remove();
    }
}