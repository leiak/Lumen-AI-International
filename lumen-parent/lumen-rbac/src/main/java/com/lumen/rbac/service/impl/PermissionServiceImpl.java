package com.lumen.rbac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lumen.rbac.entity.SysPermission;
import com.lumen.rbac.entity.SysRole;
import com.lumen.rbac.entity.SysRolePermission;
import com.lumen.rbac.mapper.SysPermissionMapper;
import com.lumen.rbac.mapper.SysRoleMapper;
import com.lumen.rbac.mapper.SysRolePermissionMapper;
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
}
