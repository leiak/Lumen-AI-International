package com.lumen.cs.exception;

import com.lumen.common.error.BizException;

public class CsException extends BizException {
    public CsException(CsErrorCode code) { super(code); }
    public CsException(CsErrorCode code, String message) { super(code, message); }
}