package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.common.security.JwtUtil;
import com.lumen.customer.dto.PortalLoginRequest;
import com.lumen.customer.dto.PortalLoginResponse;
import com.lumen.customer.dto.PortalMeResponse;
import com.lumen.customer.security.CustomerContext;
import com.lumen.customer.security.PortalAuthService;
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
        // 4.5A 简化: 重新签发 access token (沿用 active_customer_id claim, 不重做 refresh 流)
        // 4.5B 改造为完整 refresh flow (refresh token 存 Redis 黑名单等)
        String newToken = jwtUtil.issueToken(
            jwtUtil.parse(refreshToken).get("sub", Long.class),
            1L,
            List.of("customer"),
            List.of("customer:track:view"),
            Map.of("active_customer_id", jwtUtil.getActiveCustomerId(refreshToken)),
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