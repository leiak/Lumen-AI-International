package com.lumen.rbac.service;

import com.lumen.common.security.JwtUtil;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.error.RbacErrorCode;
import com.lumen.rbac.mapper.*;
import com.lumen.rbac.dto.LoginRequest;
import com.lumen.rbac.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private SysUserMapper userMapper;
    private StringRedisTemplate redis;
    private AuthServiceImpl svc;

    @BeforeEach
    void setup() {
        userMapper = mock(SysUserMapper.class);
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        JwtUtil jwt = new JwtUtil("0123456789abcdef0123456789abcdef", 900, 604800, "lumen");
        svc = new AuthServiceImpl(
                userMapper,
                mock(SysRoleMapper.class),
                mock(SysPermissionMapper.class),
                mock(SysUserRoleMapper.class),
                mock(SysRolePermissionMapper.class),
                mock(SysRefreshTokenMapper.class),
                mock(LoginAuditService.class),
                jwt,
                redis,
                new BCryptPasswordEncoder()
        );
    }

    @Test void wrongPassword_throws() {
        SysUser u = new SysUser();
        u.setId(1L); u.setTenantId(1L); u.setStatus(1);
        u.setPasswordHash(new BCryptPasswordEncoder().encode("right"));
        when(userMapper.findByUsername("alice")).thenReturn(u);
        when(userMapper.selectById(1L)).thenReturn(u);
        LoginRequest req = new LoginRequest(); req.setUsername("alice"); req.setPassword("wrong");
        assertThatThrownBy(() -> svc.login(req, "127.0.0.1", "ua"))
                .hasMessageContaining(RbacErrorCode.USER_PASSWORD_WRONG.getMessage());
    }
}
