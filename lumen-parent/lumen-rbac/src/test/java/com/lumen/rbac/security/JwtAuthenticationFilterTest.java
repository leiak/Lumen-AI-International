package com.lumen.rbac.security;

import com.lumen.common.exception.AuthException;
import com.lumen.common.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Audience routing test for {@link JwtAuthenticationFilter}.
 *
 * <p>Contract under test:
 * <ul>
 *   <li>Token without {@code aud} claim (legacy + 4-arg {@code issueToken}) → fallback {@code "admin"}</li>
 *   <li>Token with {@code aud=admin} → may access {@code /api/**} admin paths</li>
 *   <li>Token with {@code aud=portal} → may access {@code /api/auth/portal} and {@code /api/customer/**} paths only</li>
 *   <li>Mismatched audience on either side raises {@link AuthException}</li>
 * </ul>
 */
class JwtAuthenticationFilterTest {

    private JwtUtil jwtUtil;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        // Same fixture as JwtUtilTest — keep in sync.
        jwtUtil = new JwtUtil("0123456789abcdef0123456789abcdef", 900, 604800, "lumen");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        filter = new JwtAuthenticationFilter(jwtUtil, redis);
    }

    @Test
    void adminToken_onPortalEndpoint_throwsAuthException() throws Exception {
        // 4-arg issueToken defaults audience to "admin" (T1.2 backward-compat overload).
        String adminToken = jwtUtil.issueToken(1L, 1L, List.of("admin"), List.of("admin:user:view"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/customer/track");
        req.addHeader("Authorization", "Bearer " + adminToken);

        assertThrows(AuthException.class, () ->
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain()));
    }

    @Test
    void portalToken_onAdminEndpoint_throwsAuthException() throws Exception {
        String portalToken = jwtUtil.issueToken(
            2L, 1L, List.of("customer"), List.of("customer:track:view"),
            Map.of("active_customer_id", 42L), "portal");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/finance/invoice");
        req.addHeader("Authorization", "Bearer " + portalToken);

        assertThrows(AuthException.class, () ->
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain()));
    }

    @Test
    void portalToken_onPortalEndpoint_passes() throws Exception {
        String portalToken = jwtUtil.issueToken(
            2L, 1L, List.of("customer"), List.of("customer:track:view"),
            Map.of("active_customer_id", 42L), "portal");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/customer/track");
        req.addHeader("Authorization", "Bearer " + portalToken);

        assertDoesNotThrow(() ->
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain()));
    }
}
