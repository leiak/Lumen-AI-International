package com.lumen.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.rbac.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    default SysUser findByUsername(String username) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>()
                .eq("username", username).last("LIMIT 1"));
    }
}
