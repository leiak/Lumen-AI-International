package com.lumen.common.exception;

import lombok.Getter;

/**
 * Authentication/authorization exception. Carries a structured {@code code}
 * (e.g. {@code 401_001} for audience mismatch, {@code 401_002} for missing
 * token) so callers can distinguish failure modes without parsing message text.
 *
 * <p>Extends {@link RuntimeException} (not Spring Security's
 * {@code AuthenticationException}) on purpose: the filter that raises it
 * already writes a structured 401 response via Spring Security's
 * {@code ExceptionTranslationFilter} entry point, so propagating this as a
 * plain runtime exception keeps the security stack un-coupled from this
 * package.
 */
@Getter
public class AuthException extends RuntimeException {
    private final int code;

    public AuthException(int code, String message) {
        super(message);
        this.code = code;
    }
}
