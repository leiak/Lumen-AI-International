package com.lumen.customer.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustomerExceptionTest {

    @Test
    void constructor_setsCodeAndMessage() {
        CustomerException e = new CustomerException(401_002, "未激活客户身份");
        assertEquals(401_002, e.getCode());
        assertEquals("未激活客户身份", e.getMessage());
    }

    @Test
    void isRuntimeException() {
        assertTrue(new CustomerException(1, "x") instanceof RuntimeException);
    }
}