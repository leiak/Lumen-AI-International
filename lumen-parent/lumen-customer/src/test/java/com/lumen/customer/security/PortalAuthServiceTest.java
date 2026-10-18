package com.lumen.customer.security;

import com.lumen.common.exception.AuthException;
import com.lumen.common.security.JwtUtil;
import com.lumen.customer.dto.*;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.mapper.SysUserMapper;
import com.lumen.rbac.mapper.SysUserRoleMapper;
import com.lumen.wms.entity.WmsCustomer;
import com.lumen.wms.mapper.WmsCustomerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortalAuthServiceTest {

    @Mock SysUserMapper userMapper;
    @Mock SysUserRoleMapper userRoleMapper;
    @Mock WmsCustomerMapper customerMapper;
    @InjectMocks PortalAuthService service;

    // Use real JwtUtil + real PasswordEncoder so we can verify token audience / password match
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtUtil jwtUtil = new JwtUtil(
        "0123456789abcdef0123456789abcdef",  // match fixture used in JwtUtilTest
        7200L,                                 // 2h access
        604800L,                               // 7d refresh
        "lumen"
    );
    private final String RAW_PASSWORD = "password123";
    private final String HASH = passwordEncoder.encode(RAW_PASSWORD);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Manually wire real collaborators (since @InjectMocks won't pick up these final fields)
        service.passwordEncoder = passwordEncoder;
        service.jwtUtil = jwtUtil;
    }

    private SysUser userWithHash() {
        SysUser u = new SysUser();
        u.setId(2L);
        u.setUsername("alice");
        u.setPasswordHash(HASH);
        return u;
    }

    @Test
    void login_singleCustomer_returnsTokenWithAudPortal() {
        SysUser u = userWithHash();
        when(userMapper.selectByUsernameAndTenant(1L, "alice")).thenReturn(u);
        when(userRoleMapper.hasCustomerRole(2L)).thenReturn(true);
        when(userRoleMapper.listCustomerIdsForUser(2L)).thenReturn(List.of(42L));
        WmsCustomer c = new WmsCustomer(); c.setId(42L); c.setCode("C001"); c.setName("Acme");
        when(customerMapper.selectById(42L)).thenReturn(c);

        PortalLoginResponse resp = service.login(new PortalLoginRequest("alice", RAW_PASSWORD));
        assertNotNull(resp.token());
        assertEquals(42L, resp.activeCustomerId());
        assertNull(resp.choices());
        assertEquals("portal", jwtUtil.getAudience(resp.token()));
    }

    @Test
    void login_multiCustomer_returnsMfaChoiceRequiredNoToken() {
        SysUser u = userWithHash();
        when(userMapper.selectByUsernameAndTenant(1L, "alice")).thenReturn(u);
        when(userRoleMapper.hasCustomerRole(2L)).thenReturn(true);
        when(userRoleMapper.listCustomerIdsForUser(2L)).thenReturn(List.of(42L, 43L));
        WmsCustomer c1 = new WmsCustomer(); c1.setId(42L); c1.setCode("C001"); c1.setName("Acme");
        WmsCustomer c2 = new WmsCustomer(); c2.setId(43L); c2.setCode("C002"); c2.setName("Beta");
        when(customerMapper.selectBatchIds(List.of(42L, 43L))).thenReturn(List.of(c1, c2));

        PortalLoginResponse resp = service.login(new PortalLoginRequest("alice", RAW_PASSWORD));
        assertNull(resp.token());
        assertNull(resp.activeCustomerId());
        assertEquals(2, resp.choices().size());
        assertEquals(42L, resp.choices().get(0).id());
    }

    @Test
    void login_userWithoutCustomerRole_throwsAuthException() {
        SysUser u = userWithHash();
        when(userMapper.selectByUsernameAndTenant(1L, "alice")).thenReturn(u);
        when(userRoleMapper.hasCustomerRole(2L)).thenReturn(false);

        assertThrows(AuthException.class, () -> service.login(new PortalLoginRequest("alice", RAW_PASSWORD)));
    }

    @Test
    void selectCustomer_validPair_returnsToken() {
        SysUser u = userWithHash();
        when(userMapper.selectById(2L)).thenReturn(u);
        when(userRoleMapper.userLinkedToCustomer(2L, 42L)).thenReturn(true);

        PortalLoginResponse resp = service.selectCustomer(2L, 42L);
        assertNotNull(resp.token());
        assertEquals(42L, resp.activeCustomerId());
    }

    @Test
    void login_wrongPassword_throwsAuthException() {
        SysUser u = userWithHash();
        when(userMapper.selectByUsernameAndTenant(1L, "alice")).thenReturn(u);
        // hasCustomerRole and listCustomerIdsForUser are never called because password fails first

        assertThrows(AuthException.class,
            () -> service.login(new PortalLoginRequest("alice", "wrong-password")));
    }

    @Test
    void login_userNotFound_throwsAuthException() {
        when(userMapper.selectByUsernameAndTenant(1L, "ghost")).thenReturn(null);

        assertThrows(AuthException.class,
            () -> service.login(new PortalLoginRequest("ghost", RAW_PASSWORD)));
    }

    @Test
    void login_noCustomerLinks_throwsAuthException() {
        SysUser u = userWithHash();
        when(userMapper.selectByUsernameAndTenant(1L, "alice")).thenReturn(u);
        when(userRoleMapper.hasCustomerRole(2L)).thenReturn(true);
        when(userRoleMapper.listCustomerIdsForUser(2L)).thenReturn(List.of());  // empty

        assertThrows(AuthException.class,
            () -> service.login(new PortalLoginRequest("alice", RAW_PASSWORD)));
    }

    @Test
    void selectCustomer_userNotFound_throwsAuthException() {
        when(userMapper.selectById(99L)).thenReturn(null);

        assertThrows(AuthException.class,
            () -> service.selectCustomer(99L, 42L));
    }

    @Test
    void selectCustomer_userNotLinked_throwsAuthException() {
        when(userMapper.selectById(2L)).thenReturn(userWithHash());
        when(userRoleMapper.userLinkedToCustomer(2L, 99L)).thenReturn(false);

        assertThrows(AuthException.class,
            () -> service.selectCustomer(2L, 99L));
    }

    @Test
    void me_returnsChoicesWithCorrectIsPrimary() {
        SysUser u = userWithHash();
        when(userMapper.selectById(2L)).thenReturn(u);
        when(userRoleMapper.userLinkedToCustomer(2L, 42L)).thenReturn(true);
        when(userRoleMapper.listCustomerIdsForUser(2L)).thenReturn(List.of(42L, 43L));
        WmsCustomer c1 = new WmsCustomer(); c1.setId(42L); c1.setCode("C001"); c1.setName("Acme");
        WmsCustomer c2 = new WmsCustomer(); c2.setId(43L); c2.setCode("C002"); c2.setName("Beta");
        when(customerMapper.selectBatchIds(List.of(42L, 43L))).thenReturn(List.of(c1, c2));
        when(customerMapper.selectById(42L)).thenReturn(c1);

        PortalMeResponse resp = service.me(2L, 42L);
        assertEquals(2, resp.allChoices().size());
        assertTrue(resp.allChoices().stream().anyMatch(x -> x.id() == 42L && x.isPrimary()));
        assertTrue(resp.allChoices().stream().anyMatch(x -> x.id() == 43L && !x.isPrimary()));
        assertEquals("Acme", resp.activeCustomerName());
    }
}
