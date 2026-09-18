package com.lumen.rbac.security;
import com.lumen.common.security.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class JwtUtilConfig {
    @Bean
    public JwtUtil jwtUtil(JwtProperties props) {
        return new JwtUtil(props.getSecret(), props.getAccessTtlSeconds(), props.getRefreshTtlSeconds(), props.getIssuer());
    }
}
