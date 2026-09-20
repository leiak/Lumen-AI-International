package com.lumen.rbac.service;

import com.lumen.common.api.PageResult;
import com.lumen.rbac.dto.CreateRoleRequest;
import com.lumen.rbac.dto.UpdateRoleRequest;
import com.lumen.rbac.entity.SysRole;

import java.util.List;

public interface RoleService {
    PageResult<SysRole> page(long pageNum, long pageSize, String keyword);
    SysRole create(CreateRoleRequest req);
    SysRole update(Long id, UpdateRoleRequest req);
    SysRole getById(Long id);
    void delete(Long id);
    List<Long> listPermissionIds(Long roleId);
    void assignPermissions(Long roleId, List<Long> permIds);
}
