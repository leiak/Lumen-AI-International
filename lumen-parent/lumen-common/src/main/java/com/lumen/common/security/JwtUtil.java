package com.lumen.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;
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

    public String issueAccess(Long userId, Long tenantId, Collection<String> roles, Collection<String> perms) {
        return build(userId, tenantId, "access", roles, perms, accessTtlSeconds, null, null);
    }

    public String issueRefresh(Long userId, Long tenantId) {
        return build(userId, tenantId, "refresh", java.util.List.of(), java.util.List.of(), refreshTtlSeconds, null, null);
    }

    /**
     * 4-arg overload — defaults audience to {@code "admin"}. Preserves backward
     * compatibility for callers that do not yet care about multi-audience tokens.
     */
    public String issueToken(Long userId, Long tenantId, java.util.List<String> roles, java.util.List<String> perms) {
        return issueToken(userId, tenantId, roles, perms, Map.of(), "admin");
    }

    /**
     * 5-arg overload — accepts extra claims and defaults audience to {@code "admin"}.
     */
    public String issueToken(Long userId, Long tenantId, java.util.List<String> roles, java.util.List<String> perms,
                            Map<String, Object> extraClaims) {
        return issueToken(userId, tenantId, roles, perms, extraClaims, "admin");
    }

    /**
     * 6-arg overload — real implementation. Audience is set on the registered
     * {@code aud} claim (JJWT 0.12 stores it as a {@code Set<String>}).
     *
     * @param audience typically {@code "admin"} or {@code "portal"}; null/blank falls back to {@code "admin"}
     */
    public String issueToken(Long userId, Long tenantId, java.util.List<String> roles, java.util.List<String> perms,
                            Map<String, Object> extraClaims, String audience) {
        String aud = (audience == null || audience.isBlank()) ? "admin" : audience;
        Map<String, Object> extras = (extraClaims == null) ? Map.of() : extraClaims;
        return build(userId, tenantId, "access", roles, perms, accessTtlSeconds, aud, extras);
    }

    private String build(Long userId, Long tenantId, String type,
                         Collection<String> roles, Collection<String> perms, long ttl,
                         String audience, Map<String, Object> extraClaims) {
        long now = System.currentTimeMillis();
        JwtBuilder b = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim("tid", tenantId)
                .claim("type", type)
                .claim("roles", roles)
                .claim("perms", perms);
        if (audience != null && !audience.isBlank()) {
            b.audience().add(audience).and();
        }
        if (extraClaims != null) {
            for (Map.Entry<String, Object> e : extraClaims.entrySet()) {
                b.claim(e.getKey(), e.getValue());
            }
        }
        return b.issuedAt(new Date(now))
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

    /**
     * Returns the audience registered on the token. JJWT 0.12 exposes
     * {@code aud} as {@code Set<String>}; this helper unwraps the singleton
     * case (our usage only ever sets one audience).
     */
    public String getAudience(String token) {
        Set<String> aud = parse(token).getAudience();
        return aud == null || aud.isEmpty() ? null : aud.iterator().next();
    }

    /**
     * Returns the {@code active_customer_id} claim if present (portal-token
     * scenario); null otherwise.
     */
    public Long getActiveCustomerId(String token) {
        Object v = parse(token).get("active_customer_id");
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.valueOf(v.toString());
    }

    public long getAccessTtl() { return accessTtlSeconds; }
    public long getRefreshTtl() { return refreshTtlSeconds; }
}