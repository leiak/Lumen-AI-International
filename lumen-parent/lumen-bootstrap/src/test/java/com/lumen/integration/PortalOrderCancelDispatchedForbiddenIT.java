package com.lumen.integration;

import com.lumen.common.api.R;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5B.4-T4.1 — 已派车 (DISPATCHED) 的订单不可取消.
 *
 * <p>{@code DeliveryOrderServiceImpl.cancelForCustomer} 的状态 guard 只放行
 * PENDING_DISPATCH; 其它状态抛 {@code PortalBadRequestException(409_001)}, controller
 * 翻译成 {@code CustomerException(PortalErrorCode.ORDER_NOT_CANCELLABLE)} →
 * {@code CustomerExceptionHandler} → HTTP 409 + body.code=409001.
 */
@DisplayName("PortalOrder cancel (DISPATCHED) 禁止 IT (4.5B.4 T4.1)")
class PortalOrderCancelDispatchedForbiddenIT extends PortalOrderFixtureBase {

    @Test
    @DisplayName("cancel_dispatched_returns409NotCancellable")
    void cancel_dispatched_returns409NotCancellable() {
        long doId = seedDeliveryOrder(customer42Id, "DISPATCHED");

        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        ResponseEntity<R> resp = postJson(
            "/api/customer/order/" + doId + "/cancel", cancelOrderBody("不想要了"), h);

        assertThat(resp.getStatusCode().value())
            .as("PortalErrorCode.ORDER_NOT_CANCELLABLE.httpStatus=409")
            .isEqualTo(409);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(409001);
        assertThat(resp.getBody().getMessage())
            .as("错误信息应说明仅 PENDING_DISPATCH 可取消")
            .contains("PENDING_DISPATCH");

        assertThat(statusOf(doId)).as("被拒绝的取消不应改动状态").isEqualTo("DISPATCHED");
        assertThat(outboxCount(doId, "cancel")).as("被拒绝的取消不应发事件").isZero();
    }
}