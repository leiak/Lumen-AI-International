package com.lumen.rbac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lumen.rbac.entity.SysPermission;
import com.lumen.rbac.entity.SysRole;
import com.lumen.rbac.entity.SysRolePermission;
import com.lumen.rbac.entity.SysUserRole;
import com.lumen.rbac.mapper.SysPermissionMapper;
import com.lumen.rbac.mapper.SysRoleMapper;
import com.lumen.rbac.mapper.SysRolePermissionMapper;
import com.lumen.rbac.mapper.SysUserRoleMapper;
import com.lumen.rbac.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final SysPermissionMapper permMapper;
    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermMapper;
    private final SysUserRoleMapper userRoleMapper;

    @Override
    public List<SysPermission> tree() {
        // MVP: 扁平返回所有权限(无 parent_id 层级)。Phase 4+ 加树形。
        return permMapper.selectList(null);
    }

    @Override
    public Map<String, List<String>> matrix() {
        // 返回 Map<roleCode, List<permissionCode>>
        Map<String, List<String>> result = new HashMap<>();
        List<SysRole> roles = roleMapper.selectList(null);
        for (SysRole r : roles) {
            List<Long> permIds = rolePermMapper.selectList(
                new QueryWrapper<SysRolePermission>()
                    .eq("role_id", r.getId())
            ).stream().map(SysRolePermission::getPermissionId).toList();
            List<String> codes = permMapper.selectBatchIds(permIds).stream().map(SysPermission::getCode).toList();
            result.put(r.getCode(), codes);
        }
        return result;
    }

    @Override
    public boolean userHasRole(Long tenantId, Long userId, String roleCode) {
        if (userId == null || roleCode == null || roleCode.isEmpty()) {
            return false;
        }
        // Resolve role by code. tenant_id filter is auto-applied by the MP tenant
        // interceptor against sys_role.tenant_id (TenantContext.get()).
        SysRole role = roleMapper.selectByCode(roleCode);
        if (role == null) {
            return false;
        }
        // Check membership in sys_user_role. tenant_id is auto-applied by the
        // tenant interceptor when TenantContext is set.
        Long count = userRoleMapper.selectCount(
            new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId)
                .eq(SysUserRole::getRoleId, role.getId())
        );
        return count != null && count > 0;
    }
}
