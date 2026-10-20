package com.lumen.integration;

import com.lumen.common.api.R;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5B.4-T4.1 — {@code POST /api/customer/order/{doId}/cancel} 取消 PENDING 单.
 *
 * <p>期望: 200 + body.data.status=CANCELLED, DO 行状态改写, remark 追加取消标记,
 * 并发 {@code DeliveryOrderStateChangedEvent}(action=cancel) 落 outbox.
 */
@DisplayName("PortalOrder cancel (PENDING) IT (4.5B.4 T4.1)")
class PortalOrderCancelPendingIT extends PortalOrderFixtureBase {

    @Test
    @DisplayName("cancel_pending_returnsCancelledAndEmitsEvent")
    @SuppressWarnings("unchecked")
    void cancel_pending_returnsCancelledAndEmitsEvent() {
        long doId = seedDeliveryOrder(customer42Id, "PENDING_DISPATCH");

        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        ResponseEntity<R> resp = postJson(
            "/api/customer/order/" + doId + "/cancel", cancelOrderBody("客户改主意"), h);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> data = dataOf(resp);
        assertThat(((Number) data.get("doId")).longValue()).isEqualTo(doId);
        assertThat(data.get("status")).isEqualTo("CANCELLED");

        assertThat(statusOf(doId)).as("DO 行状态应落库为 CANCELLED").isEqualTo("CANCELLED");

        String remark = jdbc.queryForObject(
            "SELECT remark FROM wms_delivery_order WHERE id = ?", String.class, doId);
        assertThat(remark).as("remark 应追加取消原因留痕").contains("cancel by customer")
            .contains("客户改主意");

        assertThat(outboxCount(doId, "cancel"))
            .as("cancelForCustomer 应发 DeliveryOrderStateChangedEvent(action=cancel)")
            .isEqualTo(1);

        // 二次取消同一单 → 状态已不是 PENDING_DISPATCH, 应 409
        ResponseEntity<R> again = postJson(
            "/api/customer/order/" + doId + "/cancel", cancelOrderBody("再取消一次"), h);
        assertThat(again.getStatusCode().value())
            .as("已取消的单不可再取消 — 状态 guard 生效")
            .isEqualTo(409);
        assertThat(again.getBody().getCode()).isEqualTo(409001);
    }
}