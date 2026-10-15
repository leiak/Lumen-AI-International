package com.lumen.cs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.cs.entity.Holiday;
import org.apache.ibatis.annotations.Mapper;

/**
 * 节假日 Mapper.
 */
@Mapper
public interface HolidayMapper extends BaseMapper<Holiday> {
}