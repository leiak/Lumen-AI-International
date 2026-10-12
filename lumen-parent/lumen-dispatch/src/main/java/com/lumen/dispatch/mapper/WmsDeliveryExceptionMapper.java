package com.lumen.dispatch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.dispatch.entity.WmsDeliveryException;
import org.apache.ibatis.annotations.Mapper;

/**
 * 配送异常工单 Mapper.
 */
@Mapper
public interface WmsDeliveryExceptionMapper extends BaseMapper<WmsDeliveryException> {
}