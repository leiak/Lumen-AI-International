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
import java.util.UUID;

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
        // 【复用模式 fix】username 加 UUID 后缀，避免撞 uk_sys_user_tenant_username
        //     唯一约束（reuse mode 不清表）。
        // password 必须满足 CreateUserRequest 的 @Size(min=8) 校验
        String username = "audit-test-user-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"username\":\"" + username + "\",\"password\":\"AuditPwd123!\","
                + "\"realName\":\"Audit Test\",\"status\":1}";

        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/users", HttpMethod.POST, new HttpEntity<>(body, h), R.class);
        assertThat(resp.getStatusCode().value()).isIn(200, 201);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        // @Async 写入，轮询等待 audit log 落地；用本次的 username 过滤避免撞之前
        // 跑的残留 audit 行。
        Integer count = 0;
        for (int i = 0; i < 50; i++) {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_audit_log "
                            + "WHERE resource = 'user' AND action = 'create' "
                            + "AND uri LIKE '/api/v1/users' "
                            + "AND request LIKE ?",
                    Integer.class,
                    "%\"username\":\"" + username + "\"%");
            if (count != null && count > 0) break;
            Thread.sleep(100);
        }
        assertThat(count).isGreaterThan(0);
    }

    /**
     * 审计失败路径：POST 创建同名用户（与种子 admin 重名）触发
     * {@code uk_sys_user_tenant_username} 唯一索引冲突（DataIntegrityViolationException），
     * AuditLogAspect 的 @Around catch 路径仍写 sys_audit_log，但 status=0 + errorMsg 非空。
     *
     * <p>scenario5 验证成功路径（status=1），本测试验证失败路径
     * —— 两者一起确认 aspect 对异常的兜底写入语义没坏。
     *
     * <p>注：原本计划用 DELETE /users/{不存在}，但 {@code UserService.delete}
     * 直接 {@code mapper.deleteById} 不校验存在性，0 rows 也照样返回 200；
     * 唯一索引冲突更直接暴露 aspect 的 failure path 语义。
     */
    @Test
    void scenarioX5b_duplicateUsernamePost_writesFailureAudit() throws InterruptedException {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        String body = "{\"username\":\"admin\",\"password\":\"AuditPwd123!\","
                + "\"realName\":\"Dup Admin\",\"status\":1}";

        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/users", HttpMethod.POST, new HttpEntity<>(body, h), R.class);
        // 唯一索引冲突被 BizException 或 GlobalExceptionHandler 转成 4xx/5xx
        assertThat(resp.getStatusCode().value()).isGreaterThanOrEqualTo(400);

        // @Async 写入，轮询等审计行（action=create, status=0）
        Map<String, Object> row = null;
        for (int i = 0; i < 50; i++) {
            java.util.List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, status, error_msg AS errorMsg FROM sys_audit_log "
                            + "WHERE resource = 'user' AND action = 'create' "
                            + "AND status = 0 "
                            + "AND request LIKE '%\"username\":\"admin\"%' "
                            + "ORDER BY id DESC LIMIT 1");
            if (!rows.isEmpty()) {
                row = rows.get(0);
                break;
            }
            Thread.sleep(100);
        }
        assertThat(row).as("audit row for failed POST must exist with status=0 (async write)").isNotNull();
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(0);
        assertThat((String) row.get("errorMsg")).isNotBlank();
    }
}