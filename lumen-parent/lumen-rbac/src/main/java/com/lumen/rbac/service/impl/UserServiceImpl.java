package com.lumen.rbac.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.error.BizException;
import com.lumen.common.tenant.TenantContext;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.entity.SysUserRole;
import com.lumen.rbac.error.RbacErrorCode;
import com.lumen.rbac.mapper.SysUserMapper;
import com.lumen.rbac.mapper.SysUserRoleMapper;
import com.lumen.rbac.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final BCryptPasswordEncoder encoder;

    @Override
    public PageResult<SysUser> page(long pageNum, long pageSize, String keyword) {
        Page<SysUser> p = Page.of(pageNum, pageSize);
        QueryWrapper<SysUser> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) qw.like("username", keyword).or().like("real_name", keyword);
        Page<SysUser> res = userMapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional
    public SysUser create(SysUser u) {
        if (userMapper.findByUsername(u.getUsername()) != null) throw BizException.of(RbacErrorCode.USERNAME_DUPLICATE);
        u.setPasswordHash(encoder.encode("123456")); // 默认密码,业务上线前必须改
        if (u.getStatus() == null) u.setStatus(1);
        if (u.getTenantId() == null) u.setTenantId(TenantContext.require());
        userMapper.insert(u);
        return u;
    }

    @Override
    public SysUser update(SysUser u) {
        u.setPasswordHash(null); userMapper.updateById(u); return userMapper.selectById(u.getId());
    }

    @Override
    public SysUser getById(Long id) { return userMapper.selectById(id); }

    @Override
    @Transactional
    public void delete(Long id) { userMapper.deleteById(id); userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", id)); }

    @Override
    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", userId));
        Long tid = TenantContext.require();
        if (roleIds == null) return;
        for (Long rid : roleIds) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId); ur.setRoleId(rid); ur.setTenantId(tid);
            userRoleMapper.insert(ur);
        }
    }

    @Override
    public void resetPassword(Long id, String newPassword) {
        SysUser u = new SysUser(); u.setId(id); u.setPasswordHash(encoder.encode(newPassword));
        userMapper.updateById(u);
    }
}
