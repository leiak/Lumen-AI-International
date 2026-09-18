package com.lumen.extension.outbox;

public enum OutboxStatus {
    PENDING, PROCESSING, DONE, FAILED, DEAD_LETTER
}