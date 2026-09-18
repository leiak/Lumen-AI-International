package com.lumen.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

public class JwtUtil {
    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;
    private final String issuer;

    public JwtUtil(String secret, long accessTtlSeconds, long refreshTtlSeconds, String issuer) {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("secret must be >= 32 chars");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
        this.issuer = issuer;
    }

    public String issueAccess(Long userId, Long tenantId, java.util.Collection<String> roles, java.util.Collection<String> perms) {
        return build(userId, tenantId, "access", roles, perms, accessTtlSeconds);
    }

    public String issueRefresh(Long userId, Long tenantId) {
        return build(userId, tenantId, "refresh", java.util.List.of(), java.util.List.of(), refreshTtlSeconds);
    }

    private String build(Long userId, Long tenantId, String type,
                         java.util.Collection<String> roles, java.util.Collection<String> perms, long ttl) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim("tid", tenantId)
                .claim("type", type)
                .claim("roles", roles)
                .claim("perms", perms)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl * 1000))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).requireIssuer(issuer).build().parseSignedClaims(token).getPayload();
    }

    public long getAccessTtl() { return accessTtlSeconds; }
    public long getRefreshTtl() { return refreshTtlSeconds; }
}
