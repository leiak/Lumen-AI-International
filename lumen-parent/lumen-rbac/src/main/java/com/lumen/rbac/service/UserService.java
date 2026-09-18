package com.lumen.rbac.service;

import com.lumen.common.api.PageResult;
import com.lumen.rbac.dto.CreateUserRequest;
import com.lumen.rbac.dto.UpdateUserRequest;
import com.lumen.rbac.entity.SysUser;

import java.util.List;

public interface UserService {
    PageResult<SysUser> page(long pageNum, long pageSize, String keyword);
    SysUser create(CreateUserRequest req);
    SysUser update(Long id, UpdateUserRequest req);
    SysUser getById(Long id);
    void delete(Long id);
    void assignRoles(Long userId, List<Long> roleIds);
    void resetPassword(Long id, String newPassword);
}
