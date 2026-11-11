package com.lumen.integration;

import com.lumen.common.api.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 4.6 — 委托单交付即计费 IT.
 *
 * <p>链路: 委托单走到 DELIVERED → 发 {@code OrderStateChangedEvent} →
 * {@code OutboxDispatcher} 轮询到该行 → 反序列化后 republish →
 * finance 的 {@code BusinessEventListener} (反射读到 {@code toState=DELIVERED}) →
 * 调 {@code BillingEngineService.accrue} → 写 {@code t_billing_accrual}
 * (business_type='ORDER', business_id=委托单 id)。
 *
 * <p><b>listener 是 {@code @Async("outboxExecutor")}</b> —— 所以断言必须 await,
 * 不能同步查完就断言 (那样会偶发红)。
 *
 * <p><b>「未交付就不提单」这条为什么不能直接 {@code untilAsserted(断言 0)}</b> ——
 * 那样会**立刻通过**, 是假绿: 断言「提单数 = 0」在 listener 还没跑到时也成立,
 * 根本没验到「listener 跑过但没提单」。本 IT 的写法是三段:
 * <ol>
 *   <li><b>等事件被 dispatcher 消费</b> —— 盯 {@code t_event_outbox.status='DONE'}。
 *       注意 <b>outbox 行不会被删除</b> ({@code OutboxDispatcher} 只把 status 置 DONE),
 *       所以「行数 &gt;= 2」只能证明「事件写进去了」, 证明不了「listener 消费过了」——
 *       这正是计划里那版写法的漏洞。</li>
 *   <li><b>活性探针</b> —— 再建一单走到 DELIVERED, 等它的 accrual 真的出现。
 *       这一步证明<b>此刻</b>异步计费链路确实在跑 (而不是压根没起来)。</li>
 *   <li><b>再断言本单 0 提单</b> —— 此时「0」才有意义。</li>
 * </ol>
 */
@DisplayName("OrderBillingAccrual IT")
class OrderBillingAccrualIT extends OrderAdminFixtureBase {

    @Test
    @DisplayName("DELIVERED → t_billing_accrual 出现 business_type=ORDER 的 OPEN 提单")
    void deliver_opensAccrual() {
        // business_type='ORDER' 的 ACTIVE 规则 —— 没有它 accrue 会走 markNoRule, 不写 accrual
        seedActiveOrderRule("weight * 2");

        String token = loginAdmin();
        HttpHeaders h = adminHeaders(token);
        Long id = orderIdOf(adminPost("/api/v1/orders", createOrderBody(), h));

        adminPost("/api/v1/orders/" + id + "/submit", null, h);
        adminPost("/api/v1/orders/" + id + "/confirm", null, h);
        adminPost("/api/v1/orders/" + id + "/start-operation", null, h);
        ResponseEntity<R> deliver = adminPost("/api/v1/orders/" + id + "/deliver", null, h);
        assertThat(deliver.getStatusCode().value()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, business_type, business_id, gross_amount, status, counterparty_id "
                        + "FROM t_billing_accrual WHERE business_type = 'ORDER' AND business_id = ?",
                    id);
            assertThat(rows).as("交付后应产生一条 ORDER 提单").hasSize(1);
            Map<String, Object> row = rows.get(0);
            assertThat(row.get("status")).isEqualTo("OPEN");
            // 规则表达式 "weight * 2", 货物重量 100kg → gross 200
            assertThat(((Number) row.get("gross_amount")).doubleValue()).isEqualTo(200.0);
            assertThat(((Number) row.get("counterparty_id")).longValue()).isEqualTo(customerId);
        });
    }

    @Test
    @DisplayName("未到 DELIVERED (CONFIRMED) 不产生提单")
    void notDelivered_doesNotOpenAccrual() {
        seedActiveOrderRule("weight * 2");

        String token = loginAdmin();
        HttpHeaders h = adminHeaders(token);
        Long id = orderIdOf(adminPost("/api/v1/orders", createOrderBody(), h));

        adminPost("/api/v1/orders/" + id + "/submit", null, h);
        adminPost("/api/v1/orders/" + id + "/confirm", null, h);

        // (1) 等 dispatcher 消费本单的事件 (status='DONE')。
        //     submit + confirm 共 2 条 ORDER 事件。**盯 status 而不是行数** ——
        //     outbox 行不会被删除, 行数证明不了「被消费过」。
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(() -> {
            Integer dispatched = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_event_outbox WHERE aggregate_type = 'ORDER' "
                        + "AND aggregate_id = ? AND status = 'DONE'",
                    Integer.class, String.valueOf(id));
            return dispatched != null && dispatched >= 2;
        });

        // (2) 活性探针 —— 再建一单走到 DELIVERED, 等它的提单出现。
        //     这一步证明异步计费链路此刻确实在跑; 没有它, 下面的「0 提单」是假绿。
        Long probeId = orderIdOf(adminPost("/api/v1/orders", createOrderBody(), h));
        adminPost("/api/v1/orders/" + probeId + "/submit", null, h);
        adminPost("/api/v1/orders/" + probeId + "/confirm", null, h);
        adminPost("/api/v1/orders/" + probeId + "/start-operation", null, h);
        adminPost("/api/v1/orders/" + probeId + "/deliver", null, h);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Integer probeAccrual = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_billing_accrual WHERE business_type = 'ORDER' AND business_id = ?",
                    Integer.class, probeId);
            assertThat(probeAccrual).as("活性探针: 走到 DELIVERED 的那一单必须真的产生提单")
                    .isGreaterThanOrEqualTo(1);
        });

        // (3) 此刻再断言本单没有提单 —— 「没交付」与「没提单」才对得上
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_billing_accrual WHERE business_type = 'ORDER' AND business_id = ?",
                Integer.class, id);
        assertThat(n).as("未到 DELIVERED 不应产生提单").isZero();
    }
}
