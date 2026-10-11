package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

/**
 * 阈值配置未找到异常.
 *
 * <p>通常发生在按 tenant 查询配置但行尚未插入的场景.
 */
public class ThresholdConfigNotFoundException extends WmsException {

    public ThresholdConfigNotFoundException(Long tenantId) {
        super(WmsErrorCode.THRESHOLD_CONFIG_NOT_FOUND,
                "阈值配置未找到: tenantId=" + tenantId);
    }
}
