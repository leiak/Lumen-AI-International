package com.lumen.rbac.security;

import com.lumen.common.exception.AuthException;
import com.lumen.common.security.JwtUtil;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

        AuthException ex = assertThrows(AuthException.class, () ->
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain()));
        assertEquals(401_001, ex.getCode());
        assertEquals("需要 portal token (aud=portal)", ex.getMessage());
    }

    @Test
    void portalToken_onAdminEndpoint_throwsAuthException() throws Exception {
        String portalToken = jwtUtil.issueToken(
            2L, 1L, List.of("customer"), List.of("customer:track:view"),
            Map.of("active_customer_id", 42L), "portal");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/finance/invoice");
        req.addHeader("Authorization", "Bearer " + portalToken);

        AuthException ex = assertThrows(AuthException.class, () ->
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain()));
        assertEquals(401_001, ex.getCode());
        assertEquals("需要 admin token (aud=admin)", ex.getMessage());
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

    @Test
    void legacyIssueAccessToken_withoutAud_onAdminEndpoint_passes() throws Exception {
        // 4-arg issueAccess does NOT set the aud claim → resolveAudience must fall back to "admin"
        // so callers that pre-date the audience claim keep working on /api/** admin paths.
        String legacyToken = jwtUtil.issueAccess(1L, 1L, List.of("admin"), List.of("admin:user:view"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users");
        req.addHeader("Authorization", "Bearer " + legacyToken);
        MockHttpServletResponse res = new MockHttpServletResponse();

        // Capture SecurityContext inside chain.doFilter — the filter clears it in the surrounding
        // finally block, so we snapshot the auth here (before the clear).
        AtomicReference<Authentication> captured = new AtomicReference<>();
        FilterChain chain = (request, response) ->
            captured.set(SecurityContextHolder.getContext().getAuthentication());

        assertDoesNotThrow(() -> filter.doFilter(req, res, chain));
        Authentication auth = captured.get();
        assertNotNull(auth, "SecurityContext should be populated by JwtAuthenticationFilter");
        assertTrue(auth.getPrincipal() instanceof RbacUserDetails,
            () -> "principal should be RbacUserDetails, got: " + auth.getPrincipal());
    }
}
