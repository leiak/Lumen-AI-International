package com.lumen.common.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

public class TenantInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(@NonNull HttpServletRequest req, @NonNull HttpServletResponse res, @NonNull Object handler) {
        // TODO(security): MVP 阶段从 X-Tenant-Id 头读；Phase 3 引入 JwtAuthenticationFilter 后改为从 SecurityContext 取，移除头部读取。
        String h = req.getHeader(TenantConstants.HEADER);
        Long tid = null;
        if (h != null && !h.isBlank()) {
            try { tid = Long.parseLong(h); } catch (NumberFormatException ignored) {}
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