package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

/**
 * 客户未找到异常.
 */
public class CustomerNotFoundException extends WmsException {

    public CustomerNotFoundException(Long id) {
        super(WmsErrorCode.CUSTOMER_NOT_FOUND, "客户未找到: id=" + id);
    }

    public CustomerNotFoundException(String code) {
        super(WmsErrorCode.CUSTOMER_NOT_FOUND, "客户未找到: code=" + code);
    }
}
