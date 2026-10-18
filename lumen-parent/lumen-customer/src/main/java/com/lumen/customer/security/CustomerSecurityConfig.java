package com.lumen.customer.security;

import com.lumen.common.security.JwtUtil;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 4.5A.2-T2.2 — Wires {@link CustomerContextFilter} as a Spring bean WITHOUT
 * registering it as a global servlet filter.
 *
 * <p>Background: the filter was previously a {@code @Component} with
 * {@code @Order(Ordered.HIGHEST_PRECEDENCE + 100)}. On some servlet
 * containers that order collides with Spring Security's {@code FilterChainProxy}
 * and the filter runs BEFORE it, finding an empty SecurityContext and
 * throwing 401 on every legitimate portal request.
 *
 * <p>Fix: stop being a global servlet filter. Instead, expose it as a named
 * bean ({@code customerContextFilter}) that {@code lumen-rbac}'s
 * {@code SecurityConfig} autowires by qualifier and inserts AFTER
 * {@code JwtAuthenticationFilter} via {@code addFilterAfter}. lumen-rbac
 * cannot import the concrete class (lumen-customer depends on lumen-rbac,
 * not vice versa), so the binding goes through the {@link jakarta.servlet.Filter}
 * super-interface and a {@code @Qualifier} name.
 *
 * <p>The {@link FilterRegistrationBean} with {@code setEnabled(false)} is
 * required because {@link CustomerContextFilter} extends
 * {@code OncePerRequestFilter} (a {@code jakarta.servlet.Filter}); without
 * this bean Spring Boot would auto-register it globally and reintroduce
 * the ordering collision.
 */
@Configuration
public class CustomerSecurityConfig {

    @Bean
    public CustomerContextFilter customerContextFilter(JwtUtil jwtUtil) {
        return new CustomerContextFilter(jwtUtil);
    }

    /**
     * Prevent Spring Boot from auto-registering {@link CustomerContextFilter}
     * as a servlet filter. The bean still exists (referenced by the security
     * chain via {@code @Qualifier("customerContextFilter")}), it just won't be
     * bound to the servlet container globally.
     */
    @Bean
    public FilterRegistrationBean<CustomerContextFilter> customerContextFilterRegistration(
            CustomerContextFilter customerContextFilter) {
        FilterRegistrationBean<CustomerContextFilter> reg = new FilterRegistrationBean<>(customerContextFilter);
        reg.setEnabled(false);
        return reg;
    }
}