package com.lumen.integration;

import com.lumen.common.api.R;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5C-1 — 门户取消订单时冲销计费应计的端到端验证.
 *
 * <p>为什么必须是 IT 而不是单测: {@code AccrualServiceImpl.release} 内部调
 * {@code StateMachineEngine.assertTransition}, 单测把它 mock 掉了, 所以
 * <b>只有</b>本 IT 能证明 V38 补的 {@code billing_accrual / OPEN_FAILED →
 * RELEASED} 边真的存在于库里.
 *
 * <p>红-绿自查方法见 {@code 4.5C-1 plan Task 5 Step 7}: 把 V38 种的那条
 * {@code t_state_transition} 行从库里删掉再跑 {@code cancel_releasesFailedAccrual},
 * 它必须红 —— 若还绿, 说明 {@code assertSmTransition} 的 from_state 还是硬编码的
 * OPEN, V38 形同虚设。
 */
@DisplayName("PortalOrder cancel 计费冲销 IT (4.5C-1)")
class PortalCancelAccrualReversalIT extends PortalOrderFixtureBase {

    @Test
    @DisplayName("OPEN accrual: 取消后变 RELEASED")
    void cancel_releasesOpenAccrual() {
        long doId = seedDeliveryOrder(customer42Id, "PENDING_DISPATCH");
        long accrualId = seedAccrual(doId, "OPEN");

        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        ResponseEntity<R> resp = postJson(
            "/api/customer/order/" + doId + "/cancel", cancelOrderBody("客户改主意"), h);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(doId)).isEqualTo("CANCELLED");
        assertThat(accrualStatusOf(accrualId))
            .as("4.5C-1: 订单取消了, 对应的应计必须从 OPEN 释放掉 —— "
                + "否则月底 finalize 会把这笔不存在的收入开成 invoice")
            .isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("OPEN_FAILED accrual: 取消后也变 RELEASED (V38 的唯一防线)")
    void cancel_releasesFailedAccrual() {
        long doId = seedDeliveryOrder(customer42Id, "PENDING_DISPATCH");
        long accrualId = seedAccrual(doId, "OPEN_FAILED");

        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        ResponseEntity<R> resp = postJson(
            "/api/customer/order/" + doId + "/cancel", cancelOrderBody("客户改主意"), h);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(doId)).isEqualTo("CANCELLED");
        assertThat(accrualStatusOf(accrualId))
            .as("4.5C-1 + V38: 计费失败的应计也必须能被门户取消释放, "
                + "否则 OPEN_FAILED 仍是没有任何出边的死状态")
            .isEqualTo("RELEASED");
    }

    private String accrualStatusOf(long accrualId) {
        return jdbc.queryForObject(
            "SELECT status FROM t_billing_accrual WHERE id = ?", String.class, accrualId);
    }
}
