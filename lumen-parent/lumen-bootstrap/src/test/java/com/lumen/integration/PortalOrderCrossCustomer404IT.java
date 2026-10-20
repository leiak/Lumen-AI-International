package com.lumen.integration;

import com.lumen.common.api.R;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5B.4-T4.1 — 跨客户访问订单 → uniform 404.
 *
 * <p>{@code cancelForCustomer} / {@code getDetailForCustomer} 对 (跨租户 / 跨客户 / 不存在)
 * 三种情况抛同一个 {@code DeliveryOrderNotFoundException}, controller 再翻译成
 * {@code CustomerException(404_001)} → HTTP 404 + body.code=404001. 统一错误码是刻意的:
 * 不能让 customer B 通过"404 vs 403 vs 400"的差异推断出 customer A 的订单是否存在.
 */
@DisplayName("PortalOrder 跨客户 404 IT (4.5B.4 T4.1)")
class PortalOrderCrossCustomer404IT extends PortalOrderFixtureBase {

    @Test
    @DisplayName("cancel_otherCustomersOrder_returnsUniform404")
    void cancel_otherCustomersOrder_returnsUniform404() {
        long aliceOrderId = seedDeliveryOrder(customer42Id, "PENDING_DISPATCH");
        long carolOrderId = seedDeliveryOrder(customer99Id, "PENDING_DISPATCH");

        // carol (customer 99) 拿 token, 去取消 alice (customer 42) 的单
        String carolToken = loginAsPortal(CAROL, CAROL_PWD);
        HttpHeaders carolHeaders = portalHeaders(carolToken);

        ResponseEntity<R> cancelResp = postJson(
            "/api/customer/order/" + aliceOrderId + "/cancel",
            cancelOrderBody("我要退别人的单"), carolHeaders);

        assertThat(cancelResp.getStatusCode().value()).isEqualTo(404);
        assertThat(cancelResp.getBody()).isNotNull();
        assertThat(cancelResp.getBody().getCode())
            .as("跨客户取消应与\"不存在\"返回同一个 code (不泄漏存在性)")
            .isEqualTo(404001);
        assertThat(cancelResp.getBody().getMessage()).isEqualTo("订单不存在或无权访问");

        // 详情端点同样 uniform 404
        ResponseEntity<R> detailResp = getJson(
            "/api/customer/order/" + aliceOrderId, carolHeaders);
        assertThat(detailResp.getStatusCode().value()).isEqualTo(404);
        assertThat(detailResp.getBody().getCode()).isEqualTo(404001);

        // 不存在的 id 与跨客户 id 返回完全一致的响应
        ResponseEntity<R> missingResp = getJson(
            "/api/customer/order/999999999", carolHeaders);
        assertThat(missingResp.getStatusCode().value()).isEqualTo(404);
        assertThat(missingResp.getBody().getCode()).isEqualTo(404001);
        assertThat(missingResp.getBody().getMessage())
            .as("\"不存在\"与\"跨客户\"的 message 必须一致, 否则可被用来探测 id")
            .isEqualTo(cancelResp.getBody().getMessage());

        // 被拒绝的取消不应改动任何状态
        assertThat(statusOf(aliceOrderId)).isEqualTo("PENDING_DISPATCH");
        assertThat(statusOf(carolOrderId)).isEqualTo("PENDING_DISPATCH");

        // carol 仍能访问自己的单 (sanity: 404 不是权限被整体拒绝)
        ResponseEntity<R> ownResp = getJson("/api/customer/order/" + carolOrderId, carolHeaders);
        assertThat(ownResp.getStatusCode().value()).isEqualTo(200);
    }
}