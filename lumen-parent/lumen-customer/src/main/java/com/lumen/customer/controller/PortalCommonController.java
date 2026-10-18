package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.customer.dto.PortalCustomerChoice;
import com.lumen.customer.security.CustomerContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 客户门户 — 通用端点 (profile / choices) (Iteration 4.5A.2).
 *
 * <p>路由前缀 {@code /api/customer} — 由 JwtAuthenticationFilter 强制要求 {@code aud=portal}.
 */
@RestController
@RequestMapping("/api/customer")
public class PortalCommonController {

    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('customer:profile:view')")
    public R<Map<String, Object>> profile() {
        Long customerId = CustomerContext.requireCustomerId();
        // 4.5A 简化: 仅返 active customerId. 4.5B 改完整 profile 表单.
        return R.ok(Map.of(
            "customerId", customerId,
            "note", "完整 profile 4.5B 实施"));
    }

    @PutMapping("/profile")
    @PreAuthorize("hasAuthority('customer:profile:edit')")
    public R<Void> updateProfile(@RequestBody Map<String, String> body) {
        Long customerId = CustomerContext.requireCustomerId();
        // 4.5A stub: 仅记录 audit log, 不动数据
        return R.ok(null);
    }

    @GetMapping("/choices")
    @PreAuthorize("hasAuthority('customer:track:view')")
    public R<List<PortalCustomerChoice>> choices() {
        Long userId = CustomerContext.getUserId();
        Long activeId = CustomerContext.requireCustomerId();
        // 4.5A: 仅返 active; 4.5B 加 listAll
        return R.ok(List.of(new PortalCustomerChoice(activeId, "ACTIVE", "当前客户", true)));
    }
}