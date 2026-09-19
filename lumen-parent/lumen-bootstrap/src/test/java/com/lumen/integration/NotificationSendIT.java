package com.lumen.integration;

import com.lumen.common.api.R;
import com.lumen.notification.channel.ChannelProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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

    /**
     * 用 @MockBean 替换 MockEmailProvider：让它返回 SendResult(false, "smtp 421 simulated")，
     * 同时 channel() 仍返回 EMAIL；其他 Mock*Provider 不受影响。
     * @MockBean(name="mockEmailProvider") 替换 bean 名匹配的原 bean，
     * Spring 重新注入 List<ChannelProvider> 时只剩这个 mock + 没动的 SMS/IM provider。
     */
    @MockBean(name = "mockEmailProvider")
    private ChannelProvider mockEmailProvider;

    private String token;

    @BeforeEach
    void loginAsAdmin() {
        // @MockBean 是 per-test-method 全新 mock；必须 stub 一下 channel()，
        // 否则 NotificationDispatcher.providerMap().get("EMAIL") 拿到 null
        // → CHANNEL_PROVIDER_MISSING 5xx。scenario8 默认走 ok=true 路径，
        // scenario8c 在自己的方法体内覆写 send() stub 改为失败。
        when(mockEmailProvider.channel()).thenReturn("EMAIL");
        when(mockEmailProvider.send(any(ChannelProvider.SendRequest.class)))
                .thenReturn(new ChannelProvider.SendResult(true, "mock-email", null));

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

    /**
     * Provider 失败路径：注入 @MockBean 让 MockEmailProvider 返回
     * SendResult(ok=false, error="smtp 421 simulated")。NotificationDispatcher
     * 应当：
     * <ol>
     *   <li>仍返回 HTTP 200 + R.ok() —— 不向调用方暴露 provider 失败</li>
     *   <li>写入一行 notification_send_log，status=0，error_msg 包含 "smtp 421"</li>
     * </ol>
     * <p>scenario8 验证成功路径（status=1），本测试验证失败路径 —— 两者一起
     * 确认 dispatcher 的吞错 + 写日志语义没坏。
     */
    @Test
    void scenario8c_providerFailure_writesStatus0Log_andApiReturns200() {
        // 配置 mock：channel=EMAIL，send 返回失败
        when(mockEmailProvider.channel()).thenReturn("EMAIL");
        when(mockEmailProvider.send(any(ChannelProvider.SendRequest.class)))
                .thenReturn(new ChannelProvider.SendResult(false, null, "smtp 421 simulated"));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        String body = "{\"templateCode\":\"WELCOME\",\"receiver\":\"test@example.com\","
                + "\"vars\":{\"name\":\"Test\"}}";

        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/notification/send", HttpMethod.POST, new HttpEntity<>(body, h), R.class);

        // 1) HTTP 200（dispatcher 吞掉 provider 错误）
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        // 2) notification_send_log 行 status=0 + error_msg 包含 "smtp 421"
        //    WHERE 没有 status 过滤以免 scenario8 留的行干扰：取最新一行
        Map<String, Object> latest = jdbc.queryForList(
                "SELECT status, error_msg AS errorMsg FROM notification_send_log "
                        + "WHERE template_code = 'WELCOME' "
                        + "ORDER BY id DESC LIMIT 1"
        ).get(0);
        assertThat(((Number) latest.get("status")).intValue()).isEqualTo(0);
        assertThat((String) latest.get("errorMsg")).contains("smtp 421");
    }
}