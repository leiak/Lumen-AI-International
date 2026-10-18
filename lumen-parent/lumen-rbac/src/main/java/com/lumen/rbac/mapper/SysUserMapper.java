package com.lumen.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.rbac.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    default SysUser findByUsername(String username) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>()
                .eq("username", username).last("LIMIT 1"));
    }

    /**
     * 4.5A.2-T2.4 — portal login 的 tenant + username 精确查找.
     * 显式过滤 {@code deleted = 0} (MP 的 @TableLogic 拦截器对 @Select 注解 SQL 不生效).
     */
    @Select("SELECT * FROM sys_user WHERE tenant_id = #{tenantId} AND username = #{username} AND deleted = 0")
    SysUser selectByUsernameAndTenant(@Param("tenantId") Long tenantId,
                                       @Param("username") String username);
}
