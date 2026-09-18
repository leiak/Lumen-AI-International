package com.lumen.common.security;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import java.util.List;
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
}
