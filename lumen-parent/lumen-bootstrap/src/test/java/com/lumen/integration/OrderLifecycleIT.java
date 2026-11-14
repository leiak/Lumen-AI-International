package com.lumen.integration;

import com.lumen.common.api.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.6 — 货运委托单主链路 IT.
 *
 * <p>建单 → 提交 → 确认 → 开始操作 → 交付 → 结算 → 关账, 7 步连续走通,
 * 每一步都断言 {@code t_order.order_status}, 最后断言 outbox 事件数。
 *
 * <p><b>事件数是 6, 不是 7</b> —— 计划里写的「7 次转换 = 7 条 outbox 事件」算错了:
 * 建单<b>不发</b>事件 ({@code OrderServiceImpl.createInternal} 里没有
 * {@code eventBus.publish}), 真正发事件的是 {@code applyTransition}, 一次转换一条 ——
 * submit / confirm / start_operation / deliver / settle / close 共 6 次转换 = <b>6 条</b>。
 * 「7 步」里多出来的那一步是建单, 它不产生事件。本 IT 按 6 断言。
 *
 * <p><b>第 3 条用例锁的是「字段级 jakarta 约束」</b> —— DTO 上的
 * {@code @Size} / {@code @PositiveOrZero} 在 service 单测里<b>不触发</b>
 * (service 直接调用不走校验器), 只能在 IT 层锁: 结算币种传 4 个字符
 * (列宽 {@code CHAR(3)}) → 必须 HTTP 400 + body.code = 4001 (PARAM_INVALID)。
 * 这条同时锁住三件事: jakarta 约束真的生效 / {@code @Valid} 级联真的生效 /
 * {@code GlobalExceptionHandler.handleValidation} 真的翻译成 400。
 */
@DisplayName("OrderLifecycle IT")
class OrderLifecycleIT extends OrderAdminFixtureBase {

    @Test
    @DisplayName("DRAFT → PENDING_CONFIRM → CONFIRMED → IN_OPERATION → DELIVERED → SETTLED → CLOSED")
    // 名字里的 "Seven" 指 **7 个状态**, 不是 7 次转换 —— 建单**不发**事件,
    // 真正发事件的是 applyTransition 的 6 次转换 (submit / confirm / start_operation /
    // deliver / settle / close) = 6 条 outbox 事件。
    void fullLifecycle_walksThroughSixTransitionsAcrossSevenStates() {
        String token = loginAdmin();
        HttpHeaders h = adminHeaders(token);

        // 建单 → DRAFT
        Long id = orderIdOf(adminPost("/api/v1/orders", createOrderBody(), h));
        assertThat(statusOf(id)).isEqualTo("DRAFT");
        assertThat(outboxCount(id)).as("建单不发状态变更事件").isZero();

        // DRAFT → PENDING_CONFIRM
        ResponseEntity<R> submit = adminPost("/api/v1/orders/" + id + "/submit", null, h);
        assertThat(submit.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo("PENDING_CONFIRM");

        // PENDING_CONFIRM → CONFIRMED
        ResponseEntity<R> confirm = adminPost("/api/v1/orders/" + id + "/confirm", null, h);
        assertThat(confirm.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");

        // CONFIRMED → IN_OPERATION
        ResponseEntity<R> start = adminPost("/api/v1/orders/" + id + "/start-operation", null, h);
        assertThat(start.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo("IN_OPERATION");

        // IN_OPERATION → DELIVERED
        ResponseEntity<R> deliver = adminPost("/api/v1/orders/" + id + "/deliver", null, h);
        assertThat(deliver.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo("DELIVERED");

        // DELIVERED → SETTLED
        ResponseEntity<R> settle = adminPost("/api/v1/orders/" + id + "/settle", null, h);
        assertThat(settle.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo("SETTLED");

        // SETTLED → CLOSED
        ResponseEntity<R> close = adminPost("/api/v1/orders/" + id + "/close",
                reasonBody("4.6 IT 关账"), h);
        assertThat(close.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo("CLOSED");

        // 6 次状态转换 = 6 条 outbox 事件 (建单那一步不算 —— 它不发事件)
        assertThat(outboxCount(id)).as("6 次转换应产生 6 条 ORDER 事件").isEqualTo(6);
    }

    @Test
    @DisplayName("列表按 status 过滤 + 详情返回 allowedNextStates")
    void list_filtersByStatus_andDetailReturnsAllowedNextStates() {
        String token = loginAdmin();
        HttpHeaders h = adminHeaders(token);
        Long id = orderIdOf(adminPost("/api/v1/orders", createOrderBody(), h));
        adminPost("/api/v1/orders/" + id + "/submit", null, h);

        ResponseEntity<R> list = adminGet("/api/v1/orders?current=1&size=10&status=PENDING_CONFIRM", h);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        List<?> records = (List<?>) dataOf(list).get("records");
        assertThat(records).as("按 status 过滤应能查到刚提交的那一单")
                .anyMatch(m -> String.valueOf(((Map<?, ?>) m).get("id")).equals(String.valueOf(id)));

        ResponseEntity<R> detail = adminGet("/api/v1/orders/" + id, h);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = dataOf(detail);
        assertThat(data.get("orderStatus")).isEqualTo("PENDING_CONFIRM");
        // PENDING_CONFIRM 的出边含 CONFIRMED (V42 seed 的 15 行之一)
        @SuppressWarnings("unchecked")
        List<Object> allowedNext = (List<Object>) data.get("allowedNextStates");
        assertThat(allowedNext).contains("CONFIRMED");
    }

    @Test
    @DisplayName("结算币种超长 (4 个字符) → 400 + code 4001 (PARAM_INVALID), 不落库")
    void overlongSettlementCurrency_returns400_paramInvalid() {
        String token = loginAdmin();
        HttpHeaders h = adminHeaders(token);

        // t_order.settlement_currency 是 CHAR(3) —— 传 "USDD" 应被 jakarta 约束挡在 controller 口
        ResponseEntity<R> resp = adminPost("/api/v1/orders", createOrderBody("USDD"), h);

        assertThat(resp.getStatusCode().value())
                .as("字段级约束应翻译成 HTTP 400, 而不是落库后 MySQL 1406 → 500")
                .isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode())
                .as("GlobalExceptionHandler.handleValidation → PARAM_INVALID (4001)")
                .isEqualTo(4001);
    }
}
