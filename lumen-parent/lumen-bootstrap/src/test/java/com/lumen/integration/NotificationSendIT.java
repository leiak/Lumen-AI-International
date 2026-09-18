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
 * 场景 8：POST /api/v1/notification/send 携带 templateCode=WELCOME（种子），
 *         NotificationDispatcher 应写入一行 notification_send_log。
 *
 * 关键点：V5__notification.sql 种子中存在 WELCOME 模板（channel=EMAIL），
 * MockEmailProvider.send() 总是返回 ok=true，因此 notification_send_log.status=1。
 */
class NotificationSendIT extends IntegrationBase {

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
    void scenario8_sendNotification_writesSendLog() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        // WELCOME 模板内容: '欢迎 ${name} 加入'
        String body = "{\"templateCode\":\"WELCOME\",\"receiver\":\"test@example.com\","
                + "\"vars\":{\"name\":\"Test\"}}";

        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/notification/send", HttpMethod.POST, new HttpEntity<>(body, h), R.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_send_log WHERE template_code = 'WELCOME'",
                Integer.class);
        assertThat(count).isNotNull();
        assertThat(count).isGreaterThan(0);
    }
}