package com.lumen.numbering.error;

import com.lumen.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NumberingErrorCode implements ErrorCode {
    RULE_NOT_FOUND(12001, "编号规则不存在", 404),
    SEQ_EXHAUSTED(12002, "序列号耗尽", 500);

    private final int code;
    private final String message;
    private final int httpStatus;
}
