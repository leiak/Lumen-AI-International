package com.lumen.common.exception;

import org.springframework.security.core.AuthenticationException;

/**
 * 认证 / Audience 路由异常 (Iteration 4.5A).
 *
 * <p>由 {@code JwtAuthenticationFilter} 抛出:
 * <ul>
 *   <li>401_001 — 需要 admin token (aud=admin) 但收到 portal token, 反之亦然</li>
 *   <li>未来可扩展: 401_002 token expired, 401_003 token revoked, 等</li>
 * </ul>
 *
 * <p>继承 {@link AuthenticationException} 让 Spring Security 的
 * {@code ExceptionTranslationFilter} 拦下, 通过配置的
 * {@code AuthenticationEntryPoint} 写 401 JSON 响应. 不会冒到 servlet
 * container 返 500.
 *
 * <p>{@code int code} 留给上层 {@code @ControllerAdvice} / entry point
 * 把 code 字段写入 JSON body 的 {@code code} 字段.
 */
public class AuthException extends AuthenticationException {

    private final int code;

    public AuthException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
