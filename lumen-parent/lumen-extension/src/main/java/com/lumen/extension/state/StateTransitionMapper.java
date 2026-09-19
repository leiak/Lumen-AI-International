package com.lumen.extension.state;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.extension.state.StateTransition;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StateTransitionMapper extends BaseMapper<StateTransition> {
}
