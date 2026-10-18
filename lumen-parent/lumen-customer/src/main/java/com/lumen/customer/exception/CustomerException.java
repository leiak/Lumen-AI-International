package com.lumen.customer.exception;

public class CustomerException extends RuntimeException {
    private final int code;

    public CustomerException(int code, String message) { super(message); this.code = code; }
    public int getCode() { return code; }
}