package com.lumen.customer.web;

import com.lumen.common.api.R;
import com.lumen.customer.controller.PortalReconController;
import com.lumen.customer.error.PortalErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5C-2 — 参数解析阶段的异常 → 门户 400_005。
 *
 * <p>这两个异常在本仓原本都没有 handler, 落 {@code GlobalExceptionHandler} 的
 * catch-all → HTTP 500 "服务器内部错误"。商家把参数写错是入参错误, 该回 400。
 *
 * <p><b>本测试只断言 handler 的行为</b> (状态码 + code + 不泄露内部信息),
 * 不起 Spring 上下文 —— "异常确实被路由到这里"由 Task 6 的 IT 用真请求验证。
 */
@DisplayName("CustomerExceptionHandler — 参数异常翻译")
class CustomerExceptionHandlerParamTest {

    private final CustomerExceptionHandler handler = new CustomerExceptionHandler();

    /**
     * 构造一个 {@code MethodArgumentTypeMismatchException} (路径/查询参数类型不匹配)。
     *
     * <p>用一个真实方法的参数当 {@code MethodParameter} —— {@code getParameterName()}
     * 会去读它, 传 null 的话那个 getter 会 NPE。
     */
    private static MethodArgumentTypeMismatchException typeMismatch(String name, String value) throws Exception {
        Method m = PortalReconController.class.getDeclaredMethod("detail", Long.class);
        MethodParameter param = new MethodParameter(m, 0);
        return new MethodArgumentTypeMismatchException(
                value, Long.class, name, param, new IllegalArgumentException("boom"));
    }

    /** 构造一个 {@code HttpMessageNotReadableException} (畸形 JSON / 空 body)。 */
    private static HttpMessageNotReadableException notReadable(String rawJson) {
        return new HttpMessageNotReadableException(
                "JSON parse error: " + rawJson,
                new com.fasterxml.jackson.core.JsonParseException(null, "unexpected token"));
    }

    private void assert400_005(ResponseEntity<R<Void>> resp) {
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(PortalErrorCode.PORTAL_PARAM_INVALID.getCode());
        assertThat(resp.getBody().getCode()).isEqualTo(400_005);
    }

    @Test
    @DisplayName("HttpMessageNotReadableException → HTTP 400 + code 400005")
    void notReadable() {
        assert400_005(handler.handleParam(notReadable("{bad json")));
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException → HTTP 400 + code 400005")
    void typeMismatch() throws Exception {
        assert400_005(handler.handleParam(typeMismatch("id", "abc")));
    }

    @Test
    @DisplayName("畸形 JSON 的原文不回给商家")
    void doesNotLeakRawJson() {
        ResponseEntity<R<Void>> resp = handler.handleParam(notReadable("{\"secret\":\"salary=12345\"}"));
        assertThat(resp.getBody().getMessage())
                .as("响应体不能含 JSON 原文")
                .doesNotContain("salary")
                .doesNotContain("12345")
                .doesNotContain("secret");
    }

    @Test
    @DisplayName("参数名不回给商家")
    void doesNotLeakParamName() throws Exception {
        ResponseEntity<R<Void>> resp = handler.handleParam(typeMismatch("internalParamName", "abc"));
        assertThat(resp.getBody().getMessage())
                .as("响应体不能含参数名")
                .doesNotContain("internalParamName");
    }

    @Test
    @DisplayName("响应体只回门户自己的文案, 不回异常原始 message")
    void messageIsPortalOwnCopy() throws Exception {
        ResponseEntity<R<Void>> resp = handler.handleParam(typeMismatch("id", "abc"));
        assertThat(resp.getBody().getMessage())
                .isEqualTo(PortalErrorCode.PORTAL_PARAM_INVALID.getMessage());
    }
}
