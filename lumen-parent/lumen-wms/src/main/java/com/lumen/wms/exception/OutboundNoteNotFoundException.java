package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

/**
 * 出库单未找到异常.
 */
public class OutboundNoteNotFoundException extends WmsException {

    public OutboundNoteNotFoundException(Long id) {
        super(WmsErrorCode.OUTBOUND_NOT_FOUND, "出库单未找到: id=" + id);
    }

    public OutboundNoteNotFoundException(String no) {
        super(WmsErrorCode.OUTBOUND_NOT_FOUND, "出库单未找到: no=" + no);
    }
}
