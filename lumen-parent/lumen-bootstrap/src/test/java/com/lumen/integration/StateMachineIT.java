package com.lumen.integration;

import com.lumen.common.api.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景 7：国家状态机 — POST /api/v1/state-machines/country/fire
 *         body: {"from": "ACTIVE", "event": "DISABLE", "ctx": null}
 *         预期: to="DISABLED"，状态机定义在 CountryStateMachineConfig。
 *
 * 注：实际接口路径是 /api/v1/state-machines/{code}/fire（StateMachineController），
 * code 是 StateMachineRegistry 中注册的 code='country'。
 */
class StateMachineIT extends IntegrationBase {

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
    @SuppressWarnings("unchecked")
    void scenario7_countryTransition_activeToDisabled_succeeds() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);

        // state machine code='country'，从 ACTIVE 触发 DISABLE 事件 → to=DISABLED
        String body = "{\"from\":\"ACTIVE\",\"event\":\"DISABLE\",\"ctx\":null}";
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/state-machines/country/fire",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                R.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        assertThat(data).isNotNull();
        assertThat(data.get("from")).isEqualTo("ACTIVE");
        assertThat(data.get("event")).isEqualTo("DISABLE");
        assertThat(data.get("to")).isEqualTo("DISABLED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scenario7b_countryTransition_disabledToActive_succeeds() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);

        // 反向转换：DISABLED --ENABLE--> ACTIVE
        String body = "{\"from\":\"DISABLED\",\"event\":\"ENABLE\",\"ctx\":null}";
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/state-machines/country/fire",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                R.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        assertThat(data.get("to")).isEqualTo("ACTIVE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scenario7c_countryTransition_invalidEvent_returns400() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);

        // 非法转换：ACTIVE --FOO--> ACTIVE（不存在的事件，应抛 BizException BAD_REQUEST）
        String body = "{\"from\":\"ACTIVE\",\"event\":\"FOO\",\"ctx\":null}";
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/state-machines/country/fire",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                R.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getCode()).isNotEqualTo(0);
    }
}