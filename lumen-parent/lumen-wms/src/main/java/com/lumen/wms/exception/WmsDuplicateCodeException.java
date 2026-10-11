package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

/**
 * 唯一编码重复异常.
 *
 * <p>WMS 主数据（仓库 / 库位 / 货品 / 客户）业务编码唯一约束触发时抛出，
 * 用于提示前端当前租户内编码已被占用.
 */
public class WmsDuplicateCodeException extends WmsException {

    public WmsDuplicateCodeException(String resource, String code) {
        super(WmsErrorCode.DUPLICATE_CODE, resource + " code 重复: " + code);
    }
}
