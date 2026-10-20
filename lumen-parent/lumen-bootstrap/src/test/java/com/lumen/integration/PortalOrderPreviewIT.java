package com.lumen.integration;

import com.lumen.common.api.R;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5B.4-T4.1 — {@code POST /api/customer/order/preview} 估价预览.
 *
 * <p>fixture 给 customer 42 种一条 {@code t_customer_rate_override} (base=123.45, CNY),
 * 期望 preview 走 override 短路分支: {@code source=OVERRIDE}, {@code amount=123.4500},
 * {@code overrideId} = seed 出来的 id, {@code currency=CNY}.
 *
 * <p>顺带验证 preview 是纯读操作 — 不落 wms_delivery_order / t_billing_accrual / t_event_outbox.
 */
@DisplayName("PortalOrder preview IT (4.5B.4 T4.1)")
class PortalOrderPreviewIT extends PortalOrderFixtureBase {

    @Test
    @DisplayName("preview_overrideExists_returnsOverrideAmount")
    @SuppressWarnings("unchecked")
    void preview_overrideExists_returnsOverrideAmount() {
        long overrideId = seedRateOverride(customer42Id, new BigDecimal("123.4500"));

        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        ResponseEntity<R> resp = postJson(
            "/api/customer/order/preview", createOrderBody(null, null), h);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> data = dataOf(resp);
        assertThat(data.get("source")).isEqualTo("OVERRIDE");
        // R envelope 经测试端 Jackson 反序列化后数值型字段是 Double, 不能直接 cast BigDecimal
        assertThat(new BigDecimal(String.valueOf(data.get("amount"))).compareTo(new BigDecimal("123.45")))
            .as("override 固定价 123.45 应原样返回 — 实际 %s", data.get("amount"))
            .isZero();
        assertThat(((Number) data.get("overrideId")).longValue()).isEqualTo(overrideId);
        assertThat(data.get("ruleId")).as("override 路径不应带 ruleId").isNull();
        assertThat(data.get("currency")).isEqualTo("CNY");
        assertThat((String) data.get("breakdown")).contains("合同价");

        // preview 是纯读 — 不创建任何配送单
        Integer dos = jdbc.queryForObject(
            "SELECT COUNT(*) FROM wms_delivery_order WHERE customer_id = ?", Integer.class, customer42Id);
        assertThat(dos).as("preview 不创建配送单").isZero();
    }
}