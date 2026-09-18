package com.lumen.common.error;

public interface ErrorCode {
    int getCode();
    String getMessage();
    int getHttpStatus();
}