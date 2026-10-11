package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

/**
 * 单据状态非法异常 — 业务流转不符合状态机定义时抛出.
 *
 * <p>例如：DRAFT 才能 submit，非 RECEIVING 不能 receiveLine，DONE/CANCELLED
 * 不可取消，等等。统一映射为 HTTP 409.
 */
public class IllegalNoteStateException extends WmsException {

    public IllegalNoteStateException(String message) {
        super(WmsErrorCode.ILLEGAL_NOTE_STATE, message);
    }
}