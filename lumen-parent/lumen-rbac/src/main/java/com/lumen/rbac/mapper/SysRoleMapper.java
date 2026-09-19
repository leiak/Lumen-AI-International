package com.lumen.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.rbac.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * Look up a role by its code within a tenant. Returns null if not found.
     * Tenant filtering is performed via the MyBatis-Plus tenant interceptor when
     * a tenant context is set; pass null tenantId to skip tenant filter (global role lookup).
     */
    default SysRole selectByCode(String code) {
        return selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysRole>()
                .eq("code", code)
                .last("LIMIT 1")
        );
    }
}
