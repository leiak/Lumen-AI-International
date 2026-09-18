package com.lumen.integration;

import com.lumen.common.api.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景 5：POST /api/v1/users 通过 @Audit 注解触发 AuditLogAspect，
 *         异步写一行 sys_audit_log（resource='user', action='create', status=1）。
 *
 * 审计日志使用 @Async 写入，需要等待异步线程执行完才能 query 到。
 * 这里用了一个短轮询（最多 5 秒，每次 100ms）。
 */
class AuditLoggingIT extends IntegrationBase {

    @Autowired
    JdbcTemplate jdbc;
    private String token;

    @BeforeEach
    void loginAsAdmin() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"tenantId\":1,\"username\":\"admin\",\"password\":\"admin123\"}";
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/auth/login", HttpMethod.POST, new HttpEntity<>(body, h), R.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        token = (String) data.get("accessToken");
    }

    @Test
    void scenario5_postUser_writesAuditLog() throws InterruptedException {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        // password 必须满足 CreateUserRequest 的 @Size(min=8) 校验
        String body = "{\"username\":\"audit-test-user\",\"password\":\"AuditPwd123!\","
                + "\"realName\":\"Audit Test\",\"status\":1}";

        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/users", HttpMethod.POST, new HttpEntity<>(body, h), R.class);
        assertThat(resp.getStatusCode().value()).isIn(200, 201);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        // @Async 写入，轮询等待 audit log 落地
        Integer count = 0;
        for (int i = 0; i < 50; i++) {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_audit_log "
                            + "WHERE resource = 'user' AND action = 'create' "
                            + "AND uri LIKE '/api/v1/users'",
                    Integer.class);
            if (count != null && count > 0) break;
            Thread.sleep(100);
        }
        assertThat(count).isGreaterThan(0);
    }
}