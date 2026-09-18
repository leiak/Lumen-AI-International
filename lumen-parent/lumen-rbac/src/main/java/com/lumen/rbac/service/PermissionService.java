package com.lumen.rbac.service;

import com.lumen.rbac.entity.SysPermission;

import java.util.List;
import java.util.Map;

public interface PermissionService {
    List<SysPermission> tree();
    Map<String, List<String>> matrix();
}
