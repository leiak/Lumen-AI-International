package com.lumen.rbac.controller;

import com.lumen.common.api.R;
import com.lumen.rbac.dto.LoginRequest;
import com.lumen.rbac.dto.TokenResponse;
import com.lumen.rbac.security.RbacUserDetails;
import com.lumen.rbac.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/login")
    public R<TokenResponse> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        return R.ok(authService.login(req, getIp(http), http.getHeader("User-Agent")));
    }

    @PostMapping("/refresh")
    public R<TokenResponse> refresh(@RequestBody java.util.Map<String, String> body) {
        return R.ok(authService.refresh(body.get("refreshToken")));
    }

    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            authService.logout(auth.substring(7));
        }
        return R.ok(null);
    }

    @GetMapping("/me")
    public R<RbacUserDetails> me(@AuthenticationPrincipal RbacUserDetails me) {
        return R.ok(me);
    }

    private String getIp(HttpServletRequest req) {
        String h = req.getHeader("X-Forwarded-For");
        return h != null ? h.split(",")[0].trim() : req.getRemoteAddr();
    }
}
