package com.lumen.common.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

public class TenantInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(@NonNull HttpServletRequest req, @NonNull HttpServletResponse res, @NonNull Object handler) {
        // 优先级：JwtAuthenticationFilter 设置的 request attribute（来自 JWT 的 tid claim）> 兜底 X-Tenant-Id 头
        // X-Tenant-Id 头已不再可信任为唯一来源；MVP 期间保留作为非 JWT 路由的兜底（Phase 8+ 计划移除）。
        Object attr = req.getAttribute(TenantConstants.ATTR);
        Long tid = null;
        if (attr instanceof Long l) {
            tid = l;
        } else {
            String h = req.getHeader(TenantConstants.HEADER);
            if (h != null && !h.isBlank()) {
                try { tid = Long.parseLong(h); } catch (NumberFormatException ignored) {}
            }
        }
        if (tid != null) {
            TenantContext.set(tid);
            req.setAttribute(TenantConstants.ATTR, tid);
        }
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest req, @NonNull HttpServletResponse res,
                                @NonNull Object handler, Exception ex) {
        TenantContext.clear();
    }
}
