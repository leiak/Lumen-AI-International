package com.lumen.customer.security;

import com.lumen.common.security.JwtUtil;
import com.lumen.customer.exception.CustomerException;
import com.lumen.rbac.security.RbacUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class CustomerContextFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

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