package com.lumen.rbac.security;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
@Data @Configuration @ConfigurationProperties(prefix = "lumen.jwt")
public class JwtProperties {
    private String secret;
    private String issuer = "lumen";
    private long accessTtlSeconds = 900;
    private long refreshTtlSeconds = 604800;
}
