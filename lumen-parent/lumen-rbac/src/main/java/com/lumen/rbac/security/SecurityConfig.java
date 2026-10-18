package com.lumen.rbac.security;

import com.lumen.common.api.R;
import com.lumen.common.error.CommonErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final JwtProperties jwtProps;
    private final ObjectMapper objectMapper;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    /**
     * The {@code customerContextFilter} parameter is injected by bean name
     * (qualifier) to avoid a compile-time dependency on {@code lumen-customer}.
     * The bean itself is defined in {@code com.lumen.customer.security}
     * (see CustomerSecurityConfig there). lumen-rbac does not (and cannot)
     * import the concrete filter class because lumen-customer depends on
     * lumen-rbac, not the other way around.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           @Qualifier("customerContextFilter") Filter customerContextFilter) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh",
                                 "/api/auth/portal/login", "/api/auth/portal/refresh",
                                 // 4.5A.3-T3.5: select-customer is part of the auth flow
                                 // (userId comes from request body, not from a Bearer token).
                                 // Mirror /login + /refresh permit semantics.
                                 "/api/auth/portal/select-customer",
                                 "/api/v1/health", "/v3/api-docs/**", "/swagger-ui/**",
                                 "/swagger-ui.html", "/actuator/health").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> writeJson(res, 401, R.fail(CommonErrorCode.UNAUTHORIZED.getCode(), "未认证")))
                .accessDeniedHandler((req, res, e) -> writeJson(res, 403, R.fail(CommonErrorCode.FORBIDDEN.getCode(), "无权限"))))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(customerContextFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(HttpServletResponse res, int status, Object body) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(objectMapper.writeValueAsString(body));
    }
}