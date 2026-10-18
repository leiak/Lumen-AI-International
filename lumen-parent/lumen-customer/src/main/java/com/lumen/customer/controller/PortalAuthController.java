package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.common.security.JwtUtil;
import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.dto.PortalLoginRequest;
import com.lumen.customer.dto.PortalLoginResponse;
import com.lumen.customer.dto.PortalMeResponse;
import com.lumen.customer.dto.PortalSelectCustomerRequest;
import com.lumen.customer.security.CustomerContext;
import com.lumen.customer.security.PortalAuthService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 客户门户认证端点 (Iteration 4.5A.2).
 *
 * <p>路由前缀 {@code /api/auth/portal} — 由 JwtAuthenticationFilter 强制要求 {@code aud=portal}.
 */
@RestController
@RequestMapping("/api/auth/portal")
public class PortalAuthController {

    /**
     * 4.5A 单租户 portal fallback (Bug 1 fix). Hard-coded tenant=1 because portal
     * in MVP is single-tenant; multi-tenant portal support arrives in 4.5B+
     * (tenant resolved from subdomain / SAML IdP claim, not this hardcoded fallback).
     *
     * <p>The {@code TenantInterceptor} would normally seed {@link TenantContext} from
     * the JWT {@code tid} claim (via {@code JwtAuthenticationFilter}) or fall back to
     * the {@code X-Tenant-Id} header. But these three endpoints don't always have
     * either:
     * <ul>
     *   <li>{@code /login} has no Bearer header (the whole point is to obtain a token).</li>
     *   <li>{@code /refresh} only carries {@code X-Refresh-Token}, not {@code Authorization}.</li>
     *   <li>{@code /select-customer} is part of the auth flow, no Bearer token yet
     *       (FE echoes {@code userId} from the login response).</li>
     * </ul>
     *
     * <p>Note: I tried declaring this as a {@link ModelAttribute @ModelAttribute} void
     * method so it'd auto-run before each handler, but Spring MVC in this codebase
     * (5+/6.1) does NOT auto-invoke void no-param @ModelAttribute methods reliably —
     * the tenant context stays null on the very endpoint that needs it. So each handler
     * explicitly calls {@link #ensureDefaultTenant()} as its first line.
     */
    private static final long DEFAULT_TENANT_ID = 1L;

    private final PortalAuthService portalAuthService;
    private final JwtUtil jwtUtil;

    public PortalAuthController(PortalAuthService portalAuthService, JwtUtil jwtUtil) {
        this.portalAuthService = portalAuthService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Bug 1 fix — ensure {@link TenantContext} is populated before MyBatis Plus's multi-tenant
     * interceptor (which would otherwise inject {@code AND tenant_id = 0} and silently swallow
     * all queries). Mirrors what {@code TenantInterceptor} already does for non-portal routes
     * when {@code X-Tenant-Id} header is present, but provides an in-controller fallback for
     * the auth flow where neither Bearer nor X-Tenant-Id may be set.
     */
    private void ensureDefaultTenant() {
        if (TenantContext.get() == null) {
            TenantContext.set(DEFAULT_TENANT_ID);
        }
    }

    @PostMapping("/login")
    public R<PortalLoginResponse> login(@Valid @RequestBody PortalLoginRequest req) {
        ensureDefaultTenant();
        return R.ok(portalAuthService.login(req));
    }

    /**
     * Bug 2 fix — read {@code userId} from the request DTO rather than from
     * {@link CustomerContext}. The select-customer endpoint is invoked immediately after
     * a multi-customer login response, which carries {@code userId} back to the FE; the
     * FE echoes it on this call. {@code CustomerContextFilter} is intentionally NOT
     * extended to cover {@code /api/auth/portal/select-customer} because at this step the
     * client has no aud=portal Bearer token yet.
     */
    @PostMapping("/select-customer")
    public R<PortalLoginResponse> selectCustomer(@RequestBody PortalSelectCustomerRequest req) {
        ensureDefaultTenant();
        return R.ok(portalAuthService.selectCustomer(req.userId(), req.customerId()));
    }

    @PostMapping("/refresh")
    public R<Map<String, String>> refresh(@RequestHeader("X-Refresh-Token") String refreshToken) {
        ensureDefaultTenant();
        // 4.5A 简化: 重新签发 access token, 保留原 token 的 tid/roles/perms/active_customer_id
        // 4.5B 改造为完整 refresh flow (refresh token 存 Redis 黑名单等)
        Claims claims = jwtUtil.parse(refreshToken);

        // Bug 3 fix — JwtUtil.issueToken writes `sub` via `.subject(String.valueOf(userId))`,
        // and JJWT 0.12's default JSON deserializer also coerces numeric claims like `tid`
        // to String when reading back. `claims.get("sub", Long.class)` and
        // `claims.get("tid", Long.class)` both throw RequiredTypeException at runtime.
        // Use the typed accessor for sub and parse explicitly; for tid, use
        // `claims.get("tid")` (returns Object) + Long parse to avoid the cast.
        String subject = claims.getSubject();
        Long sub = subject == null ? null : Long.parseLong(subject);
        Object tidObj = claims.get("tid");
        Long tid = (tidObj instanceof Number n) ? n.longValue()
            : (tidObj == null ? null : Long.valueOf(tidObj.toString()));
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) claims.get("roles", List.class);
        @SuppressWarnings("unchecked")
        List<String> perms = (List<String>) claims.get("perms", List.class);
        Long activeCustomerId = jwtUtil.getActiveCustomerId(refreshToken);

        Map<String, Object> extra = activeCustomerId == null
            ? Map.of()
            : Map.of("active_customer_id", activeCustomerId);

        String newToken = jwtUtil.issueToken(
            sub,
            tid == null ? DEFAULT_TENANT_ID : tid,
            roles == null ? List.of("customer") : roles,
            perms == null ? List.of("customer:track:view") : perms,
            extra,
            "portal");
        return R.ok(Map.of("token", newToken));
    }

    @GetMapping("/me")
    public R<PortalMeResponse> me() {
        // /me always carries a valid Bearer (filter chain), so TenantContext is set by
        // JwtAuthenticationFilter from JWT tid claim; no fallback needed. Left comment
        // for symmetry / future-proofing if me() is ever invoked without a token.
        Long userId = CustomerContext.getUserId();
        Long activeCustomerId = CustomerContext.requireCustomerId();
        return R.ok(portalAuthService.me(userId, activeCustomerId));
    }
}
