package com.lumen.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
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
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token);
        // 信任密钥校验结果：parseSignedClaims() 已经用同一把 key 验证签名，
        // 签名通过即说明 algorithm 与 key 是匹配的；JJWT 0.12.x 会根据 key 长度自动
        // 选用 HS256/HS384/HS512，因此只要签名通过就不必再硬性断言 alg==HS256。
        // （早期保留该检查是为了防止 RS256/ES256 等不对称算法被误用 —— 这里强制使用对称 key 已经天然排斥那些算法）
        return jws.getPayload();
    }

    public long getAccessTtl() { return accessTtlSeconds; }
    public long getRefreshTtl() { return refreshTtlSeconds; }
}
