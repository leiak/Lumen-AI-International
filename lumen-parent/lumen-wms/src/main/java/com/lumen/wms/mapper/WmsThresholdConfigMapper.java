package com.lumen.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.wms.entity.WmsThresholdConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * WMS 审批阈值配置 Mapper（每个租户一行）.
 */
@Mapper
public interface WmsThresholdConfigMapper extends BaseMapper<WmsThresholdConfig> {

    /**
     * 按 tenant_id 查询当前生效配置（租户级唯一约束确保最多一行）.
     */
    @Select("SELECT id, tenant_id, inb_amount_threshold, inb_qty_threshold, "
            + "out_amount_threshold, out_qty_threshold, adj_amount_threshold, "
            + "adj_qty_threshold, approval_required_when_exceed, "
            + "created_at, updated_at, deleted, version "
            + "FROM wms_threshold_config WHERE tenant_id = #{tenantId} AND deleted = 0 "
            + "LIMIT 1")
    WmsThresholdConfig selectByTenantId(@Param("tenantId") Long tenantId);
}
