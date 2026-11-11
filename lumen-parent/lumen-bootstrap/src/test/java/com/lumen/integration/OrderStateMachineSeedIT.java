package com.lumen.integration;

import com.lumen.common.api.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.6 — ORDER 状态机 seed 落地 + 非法转换 IT.
 *
 * <p>三件事:
 * <ol>
 *   <li>{@code t_state_transition} 里 {@code state_machine_code='ORDER'} 恰好 15 行,
 *       且 {@code tenant_id} 全是 0 (全局行 —— 填 1 会让其它租户看不到转换规则)。</li>
 *   <li>DRAFT 的出边恰好 3 条 (PENDING_CONFIRM / CANCELLED / VOID)。</li>
 *   <li>非法转换 (DRAFT → CONFIRMED) 返回 <b>409 + code 409_101</b>,
 *       <b>不是</b> StateMachineException 的 14001/400。</li>
 * </ol>
 *
 * <p><b>第 3 条为什么是 409 而不是 400</b>: {@code StateMachineEngine.assertTransition}
 * 抛的是 {@code StateMachineException}, 但 {@code OrderServiceImpl.requireTransition}
 * <b>不走 engine 的 assert</b> —— 它调 {@code stateMachineEngine.allowedNext(...)}
 * 自己判断, 不在允许列表里就抛 {@code OrderException(STATE_TRANSITION_INVALID)},
 * 而 {@code OrderErrorCode.STATE_TRANSITION_INVALID = 409_101 / httpStatus 409}。
 * 这条 IT 锁的就是「service 层把状态机判定翻译成 409」这一层, 不是 engine 本身。
 */
@DisplayName("OrderStateMachineSeed IT")
class OrderStateMachineSeedIT extends OrderAdminFixtureBase {

    @Test
    @DisplayName("t_state_transition: ORDER 恰好 15 行, tenant_id 全是 0")
    void seed_hasFifteenGlobalRows() {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_state_transition WHERE state_machine_code = 'ORDER' AND deleted = 0",
                Integer.class);
        assertThat(cnt).isEqualTo(15);

        Integer nonGlobal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_state_transition WHERE state_machine_code = 'ORDER' AND tenant_id <> 0",
                Integer.class);
        assertThat(nonGlobal).as("状态机 seed 必须是 tenant_id=0 的全局行").isZero();
    }

    @Test
    @DisplayName("DRAFT 的出边恰好 3 条")
    void draft_hasThreeOutgoingTransitions() {
        List<String> toStates = jdbc.queryForList(
                "SELECT to_state FROM t_state_transition WHERE state_machine_code = 'ORDER' "
                    + "AND from_state = 'DRAFT' AND deleted = 0",
                String.class);
        assertThat(toStates).containsExactlyInAnyOrder("PENDING_CONFIRM", "CANCELLED", "VOID");
    }

    @Test
    @DisplayName("DRAFT → CONFIRMED 非法: 409 + code 409_101 (不是 400)")
    void illegalTransition_returns409_101() {
        String token = loginAdmin();
        HttpHeaders h = adminHeaders(token);
        Long id = orderIdOf(adminPost("/api/v1/orders", createOrderBody(), h));

        // DRAFT 直接 confirm —— 状态机不允许
        ResponseEntity<R> resp = adminPost("/api/v1/orders/" + id + "/confirm", null, h);

        assertThat(resp.getStatusCode().value())
                .as("非法转换应 409 (StateMachineException 是 400, 已被 service 转成 409)")
                .isEqualTo(409);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(409_101);
        assertThat(statusOf(id)).as("非法转换不应改动状态").isEqualTo("DRAFT");
    }
}
