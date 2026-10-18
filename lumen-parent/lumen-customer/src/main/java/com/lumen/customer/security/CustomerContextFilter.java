package com.lumen.customer.security;

import com.lumen.common.security.JwtUtil;
import com.lumen.customer.exception.CustomerException;
import com.lumen.rbac.security.RbacUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Populates {@link CustomerContext} for portal requests under {@code /api/customer/**}.
 *
 * <p>Registered into the Spring Security filter chain by
 * {@code com.lumen.rbac.security.SecurityConfig} (placed AFTER
 * {@code JwtAuthenticationFilter} so the SecurityContext principal is already
 * populated). Intentionally NOT a {@code @Component} — previously the
 * {@code @Order(Ordered.HIGHEST_PRECEDENCE + 100)} collided with Spring
 * Security's {@code FilterChainProxy} and on some servlet containers ran
 * before it, producing false 401s on every legitimate portal request.
 */
@RequiredArgsConstructor
public class CustomerContextFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (request.getRequestURI().startsWith("/api/customer/")) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !(auth.getPrincipal() instanceof RbacUserDetails)) {
                    throw new CustomerException(401_001, "需要 portal token (aud=portal)");
                }
                RbacUserDetails principal = (RbacUserDetails) auth.getPrincipal();
                Long userId = principal.getUserId();

                // Re-parse Bearer to extract active_customer_id (not in SecurityContext)
                String authHeader = request.getHeader("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    throw new CustomerException(401_001, "需要 portal token (aud=portal)");
                }
                String token = authHeader.substring(7);
                Long activeCustomerId = jwtUtil.getActiveCustomerId(token);
                if (activeCustomerId == null) {
                    throw new CustomerException(401_002, "token 缺少 active_customer_id");
                }
                CustomerContext.set(userId, activeCustomerId);
            }
            chain.doFilter(request, response);
        } finally {
            CustomerContext.clear();
        }
    }
}