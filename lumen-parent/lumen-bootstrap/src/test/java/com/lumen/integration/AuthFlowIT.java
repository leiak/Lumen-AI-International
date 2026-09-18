package com.lumen.integration;

import com.lumen.common.api.R;
import com.lumen.rbac.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景 1：登录密码错误 → 401 + RbacErrorCode.USER_PASSWORD_WRONG(10002)
 * 场景 2：刷新过期 / 非法 refresh token → 401 + TOKEN_EXPIRED(10004) 或 TOKEN_REVOKED(10005)
 *
 * 注：注入的 rest 是 Spring Boot 自动配置的 TestRestTemplate，
 *  - 底层走 Apache HttpClient5（classpath 上有 httpclient5），避免 JDK HttpURLConnection 在 401 响应上抛 streaming 异常
 *  - 默认 NoOpResponseErrorHandler，4xx/5xx 不会抛异常
 *  - rootUri 已指向 http://localhost:{随机端口}
 */
class AuthFlowIT extends IntegrationBase {

    @Test
    void scenario1_loginWrongPassword_returns10002() {
        LoginRequest req = new LoginRequest();
        req.setTenantId(1L);
        req.setUsername("admin");
        req.setPassword("wrong-password");

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/auth/login", HttpMethod.POST, new HttpEntity<>(req, h), R.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).isNotNull();
        // USER_PASSWORD_WRONG.code == 10002
        assertThat(resp.getBody().getCode()).isEqualTo(10002);
        assertThat(resp.getBody().getCode()).isNotEqualTo(0);
    }

    @Test
    void scenario2_refreshMalformedToken_returnsAuthError() {
        // 场景 2：使用一个伪造的 refresh token 调用 refresh 接口
        // AuthServiceImpl#refresh 解析失败时会抛 TOKEN_EXPIRED 或 TOKEN_REVOKED
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String malformed = "{\"refreshToken\":\"not-a-real-token\"}";

        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(malformed, h), R.class);

        // 应该返回 401（TOKEN_EXPIRED/REVOKED 的 httpStatus 都是 401）
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isNotEqualTo(0);
    }
}
