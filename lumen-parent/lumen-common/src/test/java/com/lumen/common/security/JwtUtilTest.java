package com.lumen.common.security;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class JwtUtilTest {
    private final JwtUtil jwt = new JwtUtil("0123456789abcdef0123456789abcdef", 900, 604800, "lumen");

    @Test void issueAndParse() {
        String t = jwt.issueAccess(100L, 1L, List.of("admin"), List.of("user:list"));
        Claims c = jwt.parse(t);
        assertThat(c.getSubject()).isEqualTo("100");
        assertThat(c.get("tid", Long.class)).isEqualTo(1L);
        assertThat(c.get("type", String.class)).isEqualTo("access");
        assertThat(c.get("roles", List.class)).containsExactly("admin");
    }

    @Test void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtUtil("short", 1, 1, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void refreshRoundTrip() {
        String t = jwt.issueRefresh(100L, 1L);
        Claims c = jwt.parse(t);
        assertThat(c.getSubject()).isEqualTo("100");
        assertThat(c.get("tid", Long.class)).isEqualTo(1L);
        assertThat(c.get("type", String.class)).isEqualTo("refresh");
    }

    @Test void issueToken_defaultAudienceIsAdmin() {
        String token = jwt.issueToken(1L, 1L, List.of("admin"), List.of("admin:user:view"));
        Claims claims = jwt.parse(token);
        assertThat(claims.getAudience()).containsExactly("admin");
    }

    @Test void issueToken_withExplicitPortalAudience_setsPortal() {
        String token = jwt.issueToken(
            1L, 1L, List.of("customer"), List.of("customer:track:view"),
            Map.of("active_customer_id", 42L), "portal"
        );
        Claims claims = jwt.parse(token);
        assertThat(claims.getAudience()).containsExactly("portal");
        assertThat(claims.get("active_customer_id", Long.class)).isEqualTo(42L);
    }
}