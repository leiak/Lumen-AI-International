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

    /**
     * 刷新 token 旋转：旧 refresh 用过后必须立刻失效。
     *
     * <p>AuthServiceImpl.refresh() 的旋转协议：
     * <ol>
     *   <li>line 126 {@code redis.delete(rtKey(jti))} 把旧 JTI 从 Redis 删掉</li>
     *   <li>line 130 写入新 JTI 的 refresh → Redis</li>
     * </ol>
     *
     * <p>因此第二次再用旧 refresh 调用 → {@code redis.get(rtKey(jti))} 返回 null →
     * 抛 {@code RbacErrorCode.TOKEN_REVOKED(10005)}。这保证 stolen refresh 不能无限重放。
     *
     * <p>新 refresh 必须仍能正常 rotate（最后一次验证 Redis 状态没被前述步骤污染）。
     */
    @Test
    void refreshTokenRotation_oldRevokedAfterRotation() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);

        // 1) 登录拿到 access/refresh
        LoginRequest loginReq = new LoginRequest();
        loginReq.setTenantId(1L);
        loginReq.setUsername("admin");
        loginReq.setPassword("admin123");

        ResponseEntity<R> loginResp = rest.exchange(
                "/api/v1/auth/login", HttpMethod.POST, new HttpEntity<>(loginReq, h), R.class);
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);
        assertThat(loginResp.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> loginData =
                (java.util.Map<String, Object>) loginResp.getBody().getData();
        String oldRefresh = (String) loginData.get("refreshToken");
        assertThat(oldRefresh).isNotBlank();

        // 2) 用旧 refresh 调 /refresh → 200 + 新 refresh (jti 不同)
        String body1 = "{\"refreshToken\":\"" + oldRefresh + "\"}";
        ResponseEntity<R> r1 = rest.exchange(
                "/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(body1, h), R.class);
        assertThat(r1.getStatusCode().value()).isEqualTo(200);
        assertThat(r1.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> r1Data = (java.util.Map<String, Object>) r1.getBody().getData();
        String newRefresh = (String) r1Data.get("refreshToken");
        assertThat(newRefresh).isNotBlank();
        assertThat(newRefresh).isNotEqualTo(oldRefresh);

        // 3) 再用旧 refresh 调 /refresh → 401 (TOKEN_REVOKED, code=10005)
        String body2 = "{\"refreshToken\":\"" + oldRefresh + "\"}";
        ResponseEntity<R> r2 = rest.exchange(
                "/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(body2, h), R.class);
        assertThat(r2.getStatusCode().value()).isEqualTo(401);
        assertThat(r2.getBody()).isNotNull();
        assertThat(r2.getBody().getCode()).isEqualTo(10005);  // TOKEN_REVOKED

        // 4) 用新 refresh 调 /refresh → 200
        String body3 = "{\"refreshToken\":\"" + newRefresh + "\"}";
        ResponseEntity<R> r3 = rest.exchange(
                "/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(body3, h), R.class);
        assertThat(r3.getStatusCode().value()).isEqualTo(200);
    }
}
