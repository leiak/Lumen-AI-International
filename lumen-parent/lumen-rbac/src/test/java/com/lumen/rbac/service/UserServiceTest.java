package com.lumen.rbac.service;

import com.lumen.common.tenant.TenantContext;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.error.RbacErrorCode;
import com.lumen.rbac.mapper.SysUserMapper;
import com.lumen.rbac.mapper.SysUserRoleMapper;
import com.lumen.rbac.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
    private final UserServiceImpl svc = new UserServiceImpl(userMapper, userRoleMapper, new BCryptPasswordEncoder());

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void create_hashesDefaultPassword() {
        TenantContext.set(1L);
        when(userMapper.findByUsername("alice")).thenReturn(null);
        SysUser u = new SysUser(); u.setUsername("alice");
        SysUser created = svc.create(u);
        assertThat(created.getPasswordHash()).isNotEqualTo("123456");
        assertThat(created.getPasswordHash().length()).isGreaterThan(20);
    }

    @Test void create_duplicateUsername_throws() {
        when(userMapper.findByUsername("alice")).thenReturn(new SysUser());
        SysUser u = new SysUser(); u.setUsername("alice");
        assertThatThrownBy(() -> svc.create(u)).hasMessageContaining(RbacErrorCode.USERNAME_DUPLICATE.getMessage());
    }
}
