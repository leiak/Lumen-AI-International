package com.lumen.rbac.service;

import com.lumen.rbac.entity.SysPermission;

import java.util.List;
import java.util.Map;

public interface PermissionService {
    List<SysPermission> tree();
    Map<String, List<String>> matrix();

    /**
     * Check whether the given user holds the given role within the given tenant.
     *
     * @param tenantId tenant scope; if null, treated as global check
     * @param userId   user id
     * @param roleCode role code (e.g. "admin", "ops")
     * @return true if the user is bound to a role with the given code
     */
    boolean userHasRole(Long tenantId, Long userId, String roleCode);
}
