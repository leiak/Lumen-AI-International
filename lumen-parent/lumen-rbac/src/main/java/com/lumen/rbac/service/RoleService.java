package com.lumen.rbac.service;

import com.lumen.common.api.PageResult;
import com.lumen.rbac.entity.SysRole;

import java.util.List;

public interface RoleService {
    PageResult<SysRole> page(long pageNum, long pageSize, String keyword);
    SysRole create(SysRole r);
    SysRole update(SysRole r);
    SysRole getById(Long id);
    void delete(Long id);
    void assignPermissions(Long roleId, List<Long> permIds);
}
