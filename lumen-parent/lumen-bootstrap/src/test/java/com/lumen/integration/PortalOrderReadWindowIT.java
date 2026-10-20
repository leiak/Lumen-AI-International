package com.lumen.integration;

import com.lumen.common.api.R;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5B.4-T4.2 — 订单读端点的取件窗口 + canCancel 透出.
 *
 * <p>spec §11.3 要求 "验证 4.5B 列表返 {@code planned_pickup_window_*} 字段"。
 * plan 把这条写成了给 {@code PortalTrackIT} 加 case, 但读代码后确认放错了地方 —
 * {@code PortalTrackController} 把 service 的宽 Map 投影到窄 record
 * ({@code PortalTrackListItem} / {@code PortalTrackDetail}, 各 9/12 个字段,
 * 4.5A 设计的精简视图), 窗口压根不进那条链路。4.5B 真正消费窗口的是
 * {@code PortalOrderController} → {@code pageForCustomer} / {@code getDetailForCustomer}
 * 的原样 Map, 前端 {@code OrderList.tsx} 取件窗口列 + {@code OrderDetail.tsx}
 * 的取消按钮都吃这两个端点。故本 IT 直接打订单端点.
 *
 * <p>顺带锁 {@code canCancel}: 门户 OrderDetail 的 "取消订单" 按钮 disabled 属性
 * 直接吃它, 后端若漏这个字段, 用户点取消才撞 409.
 */
@DisplayName("PortalOrder read (取件窗口 + canCancel) IT (4.5B.4 T4.2)")
class PortalOrderReadWindowIT extends PortalOrderFixtureBase {

    private static final LocalDateTime WIN_START = LocalDateTime.of(2026, 10, 20, 10, 0, 0);
    private static final LocalDateTime WIN_END = LocalDateTime.of(2026, 10, 20, 14, 0, 0);

    @Test
    @DisplayName("list_returnsPickupWindowFields")
    @SuppressWarnings("unchecked")
    void list_returnsPickupWindowFields() {
        seedDeliveryOrderWithWindow(customer42Id, "PENDING_DISPATCH", WIN_START, WIN_END);

        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        ResponseEntity<R> resp = getJson("/api/customer/order?page=1&size=20", h);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = dataOf(resp);
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertThat(records).as("alice 应看到自己 1 条 DO").hasSize(1);

        Map<String, Object> row = records.get(0);
        assertThat(row.get("plannedPickupWindowStart"))
            .as("列表应原样透出取件窗口起点 — OrderList.tsx 取件窗口列读它")
            .isEqualTo("2026-10-20T10:00:00");
        assertThat(row.get("plannedPickupWindowEnd")).isEqualTo("2026-10-20T14:00:00");
    }

    @Test
    @DisplayName("detail_returnsPickupWindowAndCanCancel")
    void detail_returnsPickupWindowAndCanCancel() {
        long doId = seedDeliveryOrderWithWindow(customer42Id, "PENDING_DISPATCH", WIN_START, WIN_END);

        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        ResponseEntity<R> resp = getJson("/api/customer/order/" + doId, h);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = dataOf(resp);
        assertThat(data.get("plannedPickupWindowStart")).isEqualTo("2026-10-20T10:00:00");
        assertThat(data.get("plannedPickupWindowEnd")).isEqualTo("2026-10-20T14:00:00");
        assertThat(data.get("canCancel"))
            .as("PENDING_DISPATCH 是唯一可取消状态 — 门户取消按钮直接吃这个字段")
            .isEqualTo(true);
    }

    @Test
    @DisplayName("detail_dispatchedOrder_hasCanCancelFalse")
    void detail_dispatchedOrder_hasCanCancelFalse() {
        long doId = seedDeliveryOrderWithWindow(customer42Id, "DISPATCHED", WIN_START, WIN_END);

        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        ResponseEntity<R> resp = getJson("/api/customer/order/" + doId, h);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = dataOf(resp);
        assertThat(data.get("canCancel"))
            .as("已派车单前端应把取消按钮置灰 — 撞了也只能拿 409, 不如提前禁掉")
            .isEqualTo(false);
    }
}