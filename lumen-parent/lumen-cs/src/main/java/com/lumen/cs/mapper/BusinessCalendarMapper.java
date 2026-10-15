package com.lumen.cs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.cs.entity.BusinessCalendar;
import org.apache.ibatis.annotations.Mapper;

/**
 * 业务时间配置 Mapper.
 */
@Mapper
public interface BusinessCalendarMapper extends BaseMapper<BusinessCalendar> {
}