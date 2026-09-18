package com.lumen.rbac.controller;

import com.lumen.common.api.R;
import com.lumen.rbac.entity.SysPermission;
import com.lumen.rbac.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
public class PermissionController {
    private final PermissionService permissionService;

    @GetMapping
    @PreAuthorize("hasAuthority('permission:list')")
    public R<List<SysPermission>> tree() { return R.ok(permissionService.tree()); }

    @GetMapping("/matrix")
    @PreAuthorize("hasAuthority('permission:matrix')")
    public R<Map<String, List<String>>> matrix() { return R.ok(permissionService.matrix()); }
}
