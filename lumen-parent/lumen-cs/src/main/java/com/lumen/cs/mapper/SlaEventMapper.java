package com.lumen.cs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.cs.entity.SlaEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * SLA 事件 Mapper.
 */
@Mapper
public interface SlaEventMapper extends BaseMapper<SlaEvent> {
}