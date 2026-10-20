package com.lumen.integration;

import com.lumen.common.api.R;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5B.4-T4.1 — 取件时间窗校验.
 *
 * <p>{@code DeliveryOrderServiceImpl.validatePickupWindow} 的四条规则:
 * <ul>
 *   <li>起点太近 (&lt; now+2h) / 太远 (&gt; now+30d) → {@code PortalBadRequestException(400_002)}
 *       → {@code ORDER_PICKUP_WINDOW_TOO_SHORT} → HTTP 400 + code 400002;</li>
 *   <li>终点 &lt;= 起点 / &gt; 起点 + 8h / 跨日 → {@code PortalBadRequestException(400_001)}
 *       → {@code ORDER_PICKUP_WINDOW_INVALID} → HTTP 400 + code 400001.</li>
 * </ul>
 *
 * <p>注意顺序: 起点检查在前, 所以"起点合法 + 终点跨日"才会落到 400_001 分支.
 */
@DisplayName("PortalOrder 取件窗口校验 IT (4.5B.4 T4.1)")
class PortalOrderPickupWindowValidationIT extends PortalOrderFixtureBase {

    @Test
    @DisplayName("create_pickupWindowStartTooNear_returns400TooShort")
    void create_pickupWindowStartTooNear_returns400TooShort() {
        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        // 起点 = now + 1h → 违反 >= now+2h
        LocalDateTime start = LocalDateTime.now().plusHours(1).withSecond(0).withNano(0);
        ResponseEntity<R> resp = postJson(
            "/api/customer/order", createOrderBody(start, start.plusHours(4)), h);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode())
            .as("ORDER_PICKUP_WINDOW_TOO_SHORT.code=400002")
            .isEqualTo(400002);

        Integer dos = jdbc.queryForObject(
            "SELECT COUNT(*) FROM wms_delivery_order WHERE customer_id = ?",
            Integer.class, customer42Id);
        assertThat(dos).as("校验失败不应落库").isZero();
    }

    @Test
    @DisplayName("create_pickupWindowStartTooFar_returns400TooShort")
    void create_pickupWindowStartTooFar_returns400TooShort() {
        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        // 起点 = now + 31d → 违反 <= now+30d
        LocalDateTime start = LocalDateTime.now().plusDays(31).withSecond(0).withNano(0);
        ResponseEntity<R> resp = postJson(
            "/api/customer/order", createOrderBody(start, start.plusHours(4)), h);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getCode()).isEqualTo(400002);
    }

    @Test
    @DisplayName("create_pickupWindowTooLong_returns400Invalid")
    void create_pickupWindowTooLong_returns400Invalid() {
        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        // 起点合法, 但终点 = 起点 + 9h → 违反 <= 起点 + 8h
        LocalDateTime start = validWindowStart();
        ResponseEntity<R> resp = postJson(
            "/api/customer/order", createOrderBody(start, start.plusHours(9)), h);

        assertThat(resp.getStatusCode().value())
            .as("ORDER_PICKUP_WINDOW_INVALID.httpStatus=400")
            .isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode())
            .as("ORDER_PICKUP_WINDOW_INVALID.code=400001")
            .isEqualTo(400001);
    }

    @Test
    @DisplayName("create_pickupWindowCrossDay_returns400Invalid")
    void create_pickupWindowCrossDay_returns400Invalid() {
        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        // 起点 23:30, 终点次日 01:30 → 窗口跨日
        LocalDateTime start = LocalDateTime.now().plusDays(1)
            .withHour(23).withMinute(30).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);  // 次日 01:30
        ResponseEntity<R> resp = postJson(
            "/api/customer/order", createOrderBody(start, end), h);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getCode()).isEqualTo(400001);
    }

    @Test
    @DisplayName("create_pickupWindowEndBeforeStart_returns400Invalid")
    void create_pickupWindowEndBeforeStart_returns400Invalid() {
        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        LocalDateTime start = validWindowStart();
        ResponseEntity<R> resp = postJson(
            "/api/customer/order", createOrderBody(start, start.minusHours(1)), h);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getCode()).isEqualTo(400001);
    }
}