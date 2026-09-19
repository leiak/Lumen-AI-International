package com.lumen.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户工具。
 *
 * <p>本应用的 JWT subject 是 userId 字符串（参见 AuthService 登录返回）。
 * Audit 切面也是这样从 {@code auth.getName()} 取 userId。
 * 提供 {@link #getCurrentUserId()} 让 controller / service 统一获取当前操作人。
 *
 * <p>匿名访问（filter 之前 / 未登录）{@code auth.getName()} 是 {@code "anonymousUser"}，
 * 这种情况下返回 null，让调用方决定降级策略。
 */
public final class SecurityUtil {

    private SecurityUtil() {}

    /**
     * 拿当前登录用户 ID。匿名返回 null。
     */
    public static Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        try {
            return Long.parseLong(auth.getName());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
