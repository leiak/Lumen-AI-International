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
}