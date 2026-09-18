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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

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
                    chain.doFilter(req, res); return;
                }
                Long userId = Long.parseLong(c.getSubject());
                Long tenantId = c.get("tid", Long.class);
                List<String> roles = (List<String>) c.get("roles", List.class);
                List<String> perms = (List<String>) c.get("perms", List.class);
                if (tenantId != null) {
                    TenantContext.set(tenantId);
                    req.setAttribute(TenantConstants.ATTR, tenantId);
                }
                RbacUserDetails ud = new RbacUserDetails(userId, tenantId, roles == null ? List.of() : roles, perms == null ? List.of() : perms);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // 解析失败不阻断，由后续权限决策处理
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
