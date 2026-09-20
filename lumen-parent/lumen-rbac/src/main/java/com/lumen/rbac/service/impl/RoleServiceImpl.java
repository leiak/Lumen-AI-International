package com.lumen.rbac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.rbac.dto.CreateRoleRequest;
import com.lumen.rbac.dto.UpdateRoleRequest;
import com.lumen.rbac.entity.SysRole;
import com.lumen.rbac.entity.SysRolePermission;
import com.lumen.rbac.mapper.SysRoleMapper;
import com.lumen.rbac.mapper.SysRolePermissionMapper;
import com.lumen.rbac.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermMapper;

    @Override
    public PageResult<SysRole> page(long pageNum, long pageSize, String keyword) {
        Page<SysRole> p = Page.of(pageNum, pageSize);
        QueryWrapper<SysRole> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) qw.like("code", keyword).or().like("name", keyword);
        Page<SysRole> res = roleMapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional
    public SysRole create(CreateRoleRequest req) {
        SysRole r = new SysRole();
        r.setCode(req.getCode());
        r.setName(req.getName());
        r.setDescription(req.getDescription());
        r.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        if (r.getTenantId() == null) r.setTenantId(TenantContext.require());
        roleMapper.insert(r);
        return r;
    }

    @Override
    public SysRole update(Long id, UpdateRoleRequest req) {
        SysRole r = new SysRole();
        r.setId(id);
        r.setName(req.getName());
        r.setDescription(req.getDescription());
        r.setStatus(req.getStatus());
        roleMapper.updateById(r);
        return roleMapper.selectById(id);
    }

    @Override
    public SysRole getById(Long id) { return roleMapper.selectById(id); }

    @Override
    @Transactional
    public void delete(Long id) {
        roleMapper.deleteById(id);
        rolePermMapper.delete(new QueryWrapper<SysRolePermission>().eq("role_id", id));
    }

    @Override
    public List<Long> listPermissionIds(Long roleId) {
        return rolePermMapper.selectList(
            new QueryWrapper<SysRolePermission>()
                .eq("role_id", roleId)
                .eq("tenant_id", TenantContext.require())
        ).stream().map(SysRolePermission::getPermissionId).toList();
    }

    @Override
    @Transactional
    public void assignPermissions(Long roleId, List<Long> permIds) {
        rolePermMapper.delete(new QueryWrapper<SysRolePermission>().eq("role_id", roleId));
        Long tid = TenantContext.require();
        if (permIds == null) return;
        for (Long pid : permIds) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId); rp.setPermissionId(pid); rp.setTenantId(tid);
            rolePermMapper.insert(rp);
        }
    }
}
