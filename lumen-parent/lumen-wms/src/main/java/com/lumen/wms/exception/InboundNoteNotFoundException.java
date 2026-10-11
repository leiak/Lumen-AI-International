package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

/**
 * 入库单未找到异常.
 */
public class InboundNoteNotFoundException extends WmsException {

    public InboundNoteNotFoundException(Long id) {
        super(WmsErrorCode.INBOUND_NOT_FOUND, "入库单未找到: id=" + id);
    }

    public InboundNoteNotFoundException(String no) {
        super(WmsErrorCode.INBOUND_NOT_FOUND, "入库单未找到: no=" + no);
    }
}
