package com.lumen.integration;

import com.lumen.common.api.R;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5B.4-T4.1 — {@code POST /api/customer/order} 客户下单.
 *
 * <p>期望一次调用产生三处副作用:
 * <ol>
 *   <li>{@code wms_delivery_order} 新增 1 行 — sourceType=PORTAL, status=PENDING_DISPATCH,
 *       customerId 取自 JWT (不是请求体), 取件窗口两列落库;</li>
 *   <li>{@code t_billing_accrual} 新增 1 行 OPEN — sourceEventId = businessId = doId
 *       (estimateAndAccrue 的幂等键);</li>
 *   <li>{@code t_event_outbox} 新增 1 行 — aggregateType=WmsDeliveryOrder, action=create.</li>
 * </ol>
 *
 * <p>fixture 同时 seed override + ACTIVE rule: override 让 {@code estimate} 短路到
 * OVERRIDE (非 NONE, 才不会跳过 accrue), rule 让 {@code accrue} 有规则可跑 — 否则
 * accrue 会 markNoRule 且不写 accrual 行.
 */
@DisplayName("PortalOrder create IT (4.5B.4 T4.1)")
class PortalOrderCreateIT extends PortalOrderFixtureBase {

    @Test
    @DisplayName("create_returnsPendingDispatchAndWritesAccrualPlusOutboxEvent")
    @SuppressWarnings("unchecked")
    void create_returnsPendingDispatchAndWritesAccrualPlusOutboxEvent() {
        seedRateOverride(customer42Id, new BigDecimal("88.0000"));
        seedActiveLastMileRule("weight * 2");

        LocalDateTime start = validWindowStart();
        LocalDateTime end = start.plusHours(4);

        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        ResponseEntity<R> resp = postJson(
            "/api/customer/order", createOrderBody(start, end), h);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> data = dataOf(resp);
        assertThat(data.get("status")).isEqualTo("PENDING_DISPATCH");
        assertThat(data.get("sourceType")).isEqualTo("PORTAL");
        assertThat(data.get("doNo")).asString().startsWith("PO-");
        // customerId / tenantId 必须来自 JWT context, 请求体里没有这两个字段
        assertThat(((Number) data.get("customerId")).longValue()).isEqualTo(customer42Id);

        long doId = ((Number) data.get("doId")).longValue();
        assertThat(doId).isPositive();

        // --- 1. DO 行 ---
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT source_type, status, customer_id, total_weight_kg, stop_count, "
                + "planned_pickup_window_start, planned_pickup_window_end, remark "
                + "FROM wms_delivery_order WHERE id = ?", doId);
        assertThat(row.get("source_type")).isEqualTo("PORTAL");
        assertThat(row.get("status")).isEqualTo("PENDING_DISPATCH");
        assertThat(((Number) row.get("customer_id")).longValue()).isEqualTo(customer42Id);
        assertThat(((BigDecimal) row.get("total_weight_kg")).compareTo(new BigDecimal("10"))).isZero();
        assertThat(row.get("planned_pickup_window_start")).as("取件窗口起点应落库").isNotNull();
        assertThat(row.get("planned_pickup_window_end")).as("取件窗口终点应落库").isNotNull();
        assertThat((String) row.get("remark")).contains("收件地址");

        // --- 2. 计费 accrual ---
        assertThat(accrualCount(doId))
            .as("estimateAndAccrue 应写 1 行 accrual (sourceEventId=businessId=doId)")
            .isEqualTo(1);
        String accrualStatus = jdbc.queryForObject(
            "SELECT status FROM t_billing_accrual WHERE business_id = ?", String.class, doId);
        assertThat(accrualStatus).isEqualTo("OPEN");

        // --- 3. outbox 事件 ---
        assertThat(outboxCount(doId, "create"))
            .as("createForCustomer 应发 DeliveryOrderStateChangedEvent(action=create)")
            .isEqualTo(1);
    }
}