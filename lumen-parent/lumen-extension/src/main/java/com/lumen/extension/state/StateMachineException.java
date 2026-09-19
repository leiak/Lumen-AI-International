package com.lumen.extension.state;

import com.lumen.common.error.BizException;
import com.lumen.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class StateMachineException extends BizException {
    public StateMachineException(String message) {
        super(new ErrorCode() {
            @Override public int getCode() { return 14001; }
            @Override public String getMessage() { return message; }
            @Override public int getHttpStatus() { return 400; }
        });
    }
}