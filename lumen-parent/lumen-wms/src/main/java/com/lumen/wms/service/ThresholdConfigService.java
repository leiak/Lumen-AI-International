package com.lumen.wms.service;

import com.lumen.wms.dto.ThresholdConfigVO;
import com.lumen.wms.dto.UpdateThresholdConfigRequest;
import com.lumen.wms.entity.WmsThresholdConfig;

/**
 * 阈值配置服务接口（T3.1: 基础 CRUD + 持久化）.
 *
 * <p>阈值判断与跨单据联动留给后续任务.
 */
public interface ThresholdConfigService {

    /**
     * 获取当前租户的阈值配置；若不存在则自动初始化一行全 0 阈值.
     */
    WmsThresholdConfig getOrInit();

    /**
     * 按租户显式查询.
     */
    WmsThresholdConfig get();

    /**
     * 更新当前租户阈值.
     */
    WmsThresholdConfig update(UpdateThresholdConfigRequest req);

    /**
     * 实体转 VO.
     */
    ThresholdConfigVO toVO(WmsThresholdConfig entity);
}
