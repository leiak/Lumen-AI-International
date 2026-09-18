package com.lumen.rbac.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.rbac.dto.CreateRoleRequest;
import com.lumen.rbac.dto.UpdateRoleRequest;
import com.lumen.rbac.entity.SysRole;
import com.lumen.rbac.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RoleController {
    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAuthority('role:list')")
    public R<PageResult<SysRole>> page(@RequestParam(defaultValue = "1") long pageNum,
                                       @RequestParam(defaultValue = "20") long pageSize,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(roleService.page(pageNum, pageSize, keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('role:create')")
    @Audit(action = "create", resource = "role")
    public R<SysRole> create(@Valid @RequestBody CreateRoleRequest req) {
        return R.ok(roleService.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('role:view')")
    public R<SysRole> get(@PathVariable Long id) { return R.ok(roleService.getById(id)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('role:update')")
    @Audit(action = "update", resource = "role", recordResponse = false)
    public R<SysRole> update(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest req) {
        return R.ok(roleService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('role:delete')")
    @Audit(action = "delete", resource = "role")
    public R<Void> delete(@PathVariable Long id) { roleService.delete(id); return R.ok(null); }

    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('role:assign-permission')")
    @Audit(action = "assign-permissions", resource = "role", recordRequest = false)
    public R<Void> assignPermissions(@PathVariable Long id, @RequestBody List<Long> permIds) {
        roleService.assignPermissions(id, permIds); return R.ok(null);
    }
}
