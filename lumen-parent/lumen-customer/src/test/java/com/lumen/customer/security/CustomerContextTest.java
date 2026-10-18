package com.lumen.customer.security;

import com.lumen.customer.exception.CustomerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
}