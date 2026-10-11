package com.lumen.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.wms.entity.WmsInventory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存账本 Mapper.
 */
@Mapper
public interface WmsInventoryMapper extends BaseMapper<WmsInventory> {
}
