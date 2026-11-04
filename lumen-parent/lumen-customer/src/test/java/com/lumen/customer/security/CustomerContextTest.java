package com.lumen.customer.security;

import com.lumen.customer.exception.CustomerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class CustomerContextTest {

    @AfterEach
    void tearDown() { CustomerContext.clear(); }

    @Test
    void set_thenRequire_returnsValues() {
        CustomerContext.set(10L, 20L);
        assertEquals(10L, CustomerContext.getUserId());
        assertEquals(20L, CustomerContext.requireCustomerId());
    }

    @Test
    void requireCustomerId_withoutSet_throwsCustomerException() {
        assertThrows(CustomerException.class, CustomerContext::requireCustomerId);
    }

    @Test
    void setNullNull_thenGetUserId_returnsNull_requireCustomerId_throws() {
        CustomerContext.set(null, null);
        assertNull(CustomerContext.getUserId());
        assertThrows(CustomerException.class, CustomerContext::requireCustomerId);
    }

    @Test
    void setUserIdOnly_requireCustomerId_stillThrows() {
        CustomerContext.set(10L, null);
        assertEquals(10L, CustomerContext.getUserId());
        assertThrows(CustomerException.class, CustomerContext::requireCustomerId);
    }

    @Test
    void set_thenClear_bothReturnNull() {
        CustomerContext.set(10L, 20L);
        CustomerContext.clear();
        assertNull(CustomerContext.getUserId());
        assertThrows(CustomerException.class, CustomerContext::requireCustomerId);
    }

    @Test
    @DisplayName("requireUserId 返回已设置的 userId")
    void requireUserId_returnsSetValue() {
        CustomerContext.set(77L, 42L);
        assertThat(CustomerContext.requireUserId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("requireUserId 未激活 → 401_002 (不是 401_003 —— 那是登录失败的码)")
    void requireUserId_throwsWhenAbsent() {
        CustomerContext.clear();
        assertThatThrownBy(CustomerContext::requireUserId)
                .isInstanceOf(CustomerException.class)
                .extracting(e -> ((CustomerException) e).getCode())
                .isEqualTo(401_002);
    }
}