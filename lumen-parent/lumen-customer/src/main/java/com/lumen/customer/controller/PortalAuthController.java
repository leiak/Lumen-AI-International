package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.common.security.JwtUtil;
import com.lumen.customer.dto.PortalLoginRequest;
import com.lumen.customer.dto.PortalLoginResponse;
import com.lumen.customer.dto.PortalMeResponse;
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

    private final PortalAuthService portalAuthService;
    private final JwtUtil jwtUtil;

    public PortalAuthController(PortalAuthService portalAuthService, JwtUtil jwtUtil) {
        this.portalAuthService = portalAuthService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public R<PortalLoginResponse> login(@Valid @RequestBody PortalLoginRequest req) {
        return R.ok(portalAuthService.login(req));
    }

    @PostMapping("/select-customer")
    public R<PortalLoginResponse> selectCustomer(@RequestBody Map<String, Long> body) {
        Long userId = CustomerContext.getUserId();
        Long customerId = body.get("customerId");
        return R.ok(portalAuthService.selectCustomer(userId, customerId));
    }

    @PostMapping("/refresh")
    public R<Map<String, String>> refresh(@RequestHeader("X-Refresh-Token") String refreshToken) {
        // 4.5A 简化: 重新签发 access token, 保留原 token 的 tid/roles/perms/active_customer_id
        // 4.5B 改造为完整 refresh flow (refresh token 存 Redis 黑名单等)
        Claims claims = jwtUtil.parse(refreshToken);
        Long sub = claims.get("sub", Long.class);
        Long tid = claims.get("tid", Long.class);
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
            tid == null ? 1L : tid,
            roles == null ? List.of("customer") : roles,
            perms == null ? List.of("customer:track:view") : perms,
            extra,
            "portal");
        return R.ok(Map.of("token", newToken));
    }

    @GetMapping("/me")
    public R<PortalMeResponse> me() {
        Long userId = CustomerContext.getUserId();
        Long activeCustomerId = CustomerContext.requireCustomerId();
        return R.ok(portalAuthService.me(userId, activeCustomerId));
    }
}