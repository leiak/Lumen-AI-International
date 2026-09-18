package com.lumen.rbac.security;

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

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            String token = h.substring(7);
            try {
                Claims c = jwtUtil.parse(token);
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
            } catch (RedisConnectionFailureException e) {
                // Redis 不可达 — fail-closed：拒绝该 token（由 SecurityConfig 401 处理）
                log.warn("redis blacklist unreachable, denying token: jti=?", e);
            } catch (Exception e) {
                // 其他 JWT 解析失败 — 静默放行（无认证态，由后续 SecurityConfig 401 处理）
                log.debug("JWT parse failed", e);
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
