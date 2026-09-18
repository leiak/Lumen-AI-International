package com.lumen.rbac.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasAuthority('user:list')")
    public R<PageResult<SysUser>> page(@RequestParam(defaultValue = "1") long pageNum,
                                       @RequestParam(defaultValue = "20") long pageSize,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(userService.page(pageNum, pageSize, keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:create')")
    @Audit(action = "create", resource = "user")
    public R<SysUser> create(@RequestBody SysUser u) { return R.ok(userService.create(u)); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:view')")
    public R<SysUser> get(@PathVariable Long id) { return R.ok(userService.getById(id)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user:update')")
    @Audit(action = "update", resource = "user", recordResponse = false)
    public R<SysUser> update(@PathVariable Long id, @RequestBody SysUser u) { u.setId(id); return R.ok(userService.update(u)); }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('user:delete')")
    @Audit(action = "delete", resource = "user")
    public R<Void> delete(@PathVariable Long id) { userService.delete(id); return R.ok(null); }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:assign-role')")
    @Audit(action = "assign-roles", resource = "user", recordRequest = false)
    public R<Void> assignRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        userService.assignRoles(id, roleIds); return R.ok(null);
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('user:reset-password')")
    @Audit(action = "reset-password", resource = "user", recordRequest = false)
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        userService.resetPassword(id, body.get("newPassword")); return R.ok(null);
    }
}
