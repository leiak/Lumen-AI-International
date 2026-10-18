package com.lumen.rbac.security;

import com.lumen.common.exception.AuthException;
import com.lumen.common.security.JwtUtil;
import com.lumen.common.tenant.TenantConstants;
import com.lumen.common.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Error code namespace — 401_xxx = auth-stage failures. 401_001 = audience mismatch. */
    private static final int ERR_AUDIENCE_MISMATCH = 401_001;

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            String token = h.substring(7);
            Claims c;
            try {
                c = jwtUtil.parse(token);
            } catch (RedisConnectionFailureException e) {
                // Redis 不可达 — fail-closed：拒绝该 token（由 SecurityConfig 401 处理）
                log.warn("redis blacklist unreachable, denying token", e);
                try {
                    chain.doFilter(req, res);
                } finally {
                    TenantContext.clear();
                    SecurityContextHolder.clearContext();
                }
                return;
            } catch (AuthException e) {
                // Audience-routing rejection — propagate so the security entry point writes 401.
                throw e;
            } catch (Exception e) {
                // 其他 JWT 解析失败 — 静默放行（无认证态，由后续 SecurityConfig 401 处理）
                log.debug("JWT parse failed", e);
                try {
                    chain.doFilter(req, res);
                } finally {
                    TenantContext.clear();
                    SecurityContextHolder.clearContext();
                }
                return;
            }

            // 4.5A.1-T1.3 audience routing. Null aud (legacy tokens, 4-arg issueToken)
            // falls back to "admin" to preserve backward compatibility for callers that
            // pre-date the audience claim.
            String audience = resolveAudience(c);
            enforceAudienceRoute(req.getRequestURI(), audience);

            if (redis.opsForValue().get("bl:" + c.getId()) != null) {
                // 黑名单命中 — 不设置 SecurityContext，让 SecurityConfig 返回 401
                log.debug("blacklisted JWT: jti={}", c.getId());
            } else {
                Long userId = Long.parseLong(c.getSubject());
                Long tenantId = c.get("tid", Long.class);
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) c.get("roles", List.class);
                @SuppressWarnings("unchecked")
                List<String> perms = (List<String>) c.get("perms", List.class);
                if (tenantId != null) {
                    TenantContext.set(tenantId);
                    req.setAttribute(TenantConstants.ATTR, tenantId);
                }
                RbacUserDetails ud = new RbacUserDetails(userId, tenantId, roles == null ? List.of() : roles, perms == null ? List.of() : perms);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Pull the {@code aud} claim from parsed claims. JJWT 0.12 stores it as
     * {@code Set<String>}; we unwrap the singleton case and fall back to
     * {@code "admin"} when missing so legacy tokens keep working.
     */
    private String resolveAudience(Claims c) {
        Set<String> aud = c.getAudience();
        if (aud == null || aud.isEmpty()) {
            return "admin";
        }
        String first = aud.iterator().next();
        return (first == null || first.isBlank()) ? "admin" : first;
    }

    /**
     * Reject mismatched audience for the requested path. Portal paths require
     * {@code aud=portal}; everything else under {@code /api/**} is treated as
     * an admin surface and requires {@code aud=admin}.
     */
    private void enforceAudienceRoute(String path, String audience) {
        boolean isPortalPath = path.startsWith("/api/auth/portal") || path.startsWith("/api/customer/");
        boolean isApiPath = path.startsWith("/api/");
        if (isPortalPath) {
            if (!"portal".equals(audience)) {
                throw new AuthException(ERR_AUDIENCE_MISMATCH, "需要 portal token (aud=portal)");
            }
        } else if (isApiPath) {
            if (!"admin".equals(audience)) {
                throw new AuthException(ERR_AUDIENCE_MISMATCH, "需要 admin token (aud=admin)");
            }
        }
        // non-/api paths (static, swagger, etc.) are not gated here — handled by SecurityConfig.
    }
}
