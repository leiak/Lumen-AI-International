package com.lumen.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.rbac.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /**
     * 4.5A.2-T2.4 — 判定 user 是否被授予 code='customer' 的角色 (V33 中新建).
     * 显式 {@code r.deleted = 0} 过滤, 不走 @TableLogic 拦截器.
     */
    @Select("SELECT COUNT(*) > 0 FROM sys_user_role ur JOIN sys_role r ON ur.role_id = r.id WHERE ur.user_id = #{userId} AND r.code = 'customer' AND r.deleted = 0")
    boolean hasCustomerRole(@Param("userId") Long userId);

    /**
     * 4.5A.2-T2.4 — 查询 user 关联的全部客户 id (V33 中新建的 t_customer_user_link).
     * 按主账号在前、其他 id 升序的稳定序排列 — 用于多客户登录时 dropdown 排序.
     */
    @Select("SELECT customer_id FROM t_customer_user_link WHERE user_id = #{userId} AND deleted = 0 ORDER BY is_primary DESC, id ASC")
    List<Long> listCustomerIdsForUser(@Param("userId") Long userId);

    /**
     * 4.5A.2-T2.4 — selectCustomer 路径上的越权检查: user 是否真实绑定了该 customer.
     */
    @Select("SELECT COUNT(*) > 0 FROM t_customer_user_link WHERE user_id = #{userId} AND customer_id = #{customerId} AND deleted = 0")
    boolean userLinkedToCustomer(@Param("userId") Long userId,
                                  @Param("customerId") Long customerId);
}
