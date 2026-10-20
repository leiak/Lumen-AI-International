package com.lumen.integration;

import com.lumen.dispatch.event.OutboundShippedEvent;
import com.lumen.dispatch.event.WmsOutboundShippedListener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 4.5B.4-T4.2 — spec §11.3 第 1 条: "outbound 走 listener 创建的 DO 也有 {@code customer_id}".
 *
 * <p>plan/spec 都把这条写成"给既有 {@code OutboundShipEventIT} 加 assertion", 但该文件
 * 在本仓库不存在 ({@code DeliveryFlowIT} 注释也写明它"走同步创建以保持测试稳定",
 * 绕开了 listener)。所以这里新开一个 IT, 直接打 {@link WmsOutboundShippedListener} —
 * 4.5B.2 T2.7 加的 {@code customerId} 回写逻辑此前 {@code 0} 覆盖。
 *
 * <p>为什么这行代码重要: 客户门户按 {@code wms_delivery_order.customer_id} 过滤自己的
 * 订单。listener 从 outbound 建出来的单若丢了 customerId, 这些单在门户里永远不可见 —
 * 客户会看到"我明明出了货, 门户里却没有单子"。
 *
 * <p>listener 是 {@code @Async("outboxExecutor")}, 调 bean 会走代理异步执行, 因此用
 * Awaitility 轮询等行落地, 而不是同步断言。
 *
 * <p><b>fixture id 必须每次 JVM 跑都不同</b>: {@code OUTBOUND_NO_SALT} 取 nanoTime。
 * 用自增 counter 的话同一 source_no 会在多次跑之间重复 — 上一次跑残留的行会被
 * {@link #awaitOrderBySourceNo} 立刻命中, 测试变成假绿 (本 IT 踩过一次:
 * 断言失败后残留行让后续跑"通过"了一个已被改坏的 listener)。
 */
@DisplayName("OutboundShipped listener customerId 传播 IT (4.5B.4 T4.2)")
class OutboundShippedCustomerIdIT extends IntegrationBase {

    @Autowired private WmsOutboundShippedListener listener;
    @Autowired private JdbcTemplate jdbc;

    /** 与 PortalOrderFixtureBase 同段, 避开其它 IT 的占用区。 */
    private static final AtomicLong FIXTURE_ID = new AtomicLong(970_001L);

    /** 让 source_no 每个 JVM 跑都唯一 — 见类注释里踩过的坑。 */
    private static final long OUTBOUND_NO_SALT = System.nanoTime();

    /** DO.warehouse_id NOT NULL 但无 FK, 任意 BIGINT 即可。 */
    private final long warehouseId = FIXTURE_ID.incrementAndGet();
    private final long customerId = FIXTURE_ID.incrementAndGet();

    /** 本次跑用掉的 source_no — @AfterEach 兜底清理, 断言失败也不留垃圾。 */
    private final List<String> outboundNos = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String no : outboundNos) {
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id FROM wms_delivery_order WHERE source_no = ?", no);
                for (Map<String, Object> row : rows) {
                    long id = ((Number) row.get("id")).longValue();
                    jdbc.update("DELETE FROM wms_delivery_stop WHERE order_id = ?", id);
                    jdbc.update("DELETE FROM wms_delivery_order WHERE id = ?", id);
                }
            } catch (Exception ignored) {
                // fixture 清理不该让测试红
            }
        }
        outboundNos.clear();
    }

    @Test
    @DisplayName("listener_propagatesCustomerIdAndCreatesStops")
    void listener_propagatesCustomerIdAndCreatesStops() {
        String outboundNo = nextOutboundNo("CUST");

        listener.onOutboundShipped(new OutboundShippedEvent(
            this, 1L, 9_001L, outboundNo, customerId, warehouseId,
            "上海市 A 路 1 号", "张三", "13800000000", 2));

        Map<String, Object> row = awaitOrderBySourceNo(outboundNo);
        assertThat(row.get("customer_id"))
            .as("4.5B.2 T2.7: listener 应把事件里的 customerId 写进 DO — "
                + "丢了客户在门户里就看不到这单")
            .isEqualTo(customerId);
        assertThat(row.get("source_type")).isEqualTo("OUTBOUND");
        assertThat(row.get("source_no")).isEqualTo(outboundNo);
        assertThat(((Number) row.get("stop_count")).intValue()).isEqualTo(2);

        // 事件不携带取件窗口 — 取件窗口是 4.5B 门户下单独有的能力,
        // outbound 建单保持 NULL (见 listener 注释).
        assertThat(row.get("planned_pickup_window_start")).isNull();
        assertThat(row.get("planned_pickup_window_end")).isNull();

        long doId = ((Number) row.get("id")).longValue();
        List<Map<String, Object>> stops = jdbc.queryForList(
            "SELECT stop_no, address, contact_name, contact_phone, status "
                + "FROM wms_delivery_stop WHERE order_id = ? ORDER BY stop_no", doId);
        assertThat(stops).as("事件 stopCount=2 → 应建 2 个停站点").hasSize(2);
        assertThat(stops.get(0).get("address")).isEqualTo("上海市 A 路 1 号");
        assertThat(stops.get(0).get("contact_name")).isEqualTo("张三");
        assertThat(stops.get(0).get("status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("listener_nullCustomerId_leavesColumnNull")
    void listener_nullCustomerId_leavesColumnNull() {
        String outboundNo = nextOutboundNo("NOCUST");

        listener.onOutboundShipped(new OutboundShippedEvent(
            this, 1L, 9_002L, outboundNo, null, warehouseId,
            "广州市 B 路 2 号", "李四", "13900000000", 1));

        Map<String, Object> row = awaitOrderBySourceNo(outboundNo);
        assertThat(row.get("customer_id"))
            .as("customerId=null 时不该硬写一个默认值 — 这些单不属于任何客户门户账户")
            .isNull();
    }

    // ==================================================================
    //  helpers
    // ==================================================================

    private String nextOutboundNo(String tag) {
        String no = "OB-45B-" + tag + "-" + OUTBOUND_NO_SALT + "-" + FIXTURE_ID.incrementAndGet();
        outboundNos.add(no);
        return no;
    }

    /**
     * 等 listener (异步) 把 DO 建出来并返回它.
     *
     * <p>用 {@code queryForList} 而不是 {@code queryForMap} 轮询: 后者在查不到行时抛
     * {@code EmptyResultDataAccessException}, 而 Awaitility 的 {@code untilAsserted}
     * 只重试 {@code AssertionError}, 其它 Throwable 直接向上抛 — 第一次没赶上提交
     * 就会误报失败。
     */
    private Map<String, Object> awaitOrderBySourceNo(String sourceNo) {
        AtomicReference<Map<String, Object>> found = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(200))
            .until(() -> {
                List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, customer_id, source_type, source_no, stop_count, "
                        + "planned_pickup_window_start, planned_pickup_window_end "
                        + "FROM wms_delivery_order WHERE source_no = ?", sourceNo);
                if (!rows.isEmpty()) {
                    found.set(rows.get(0));
                }
                return !rows.isEmpty();
            });
        return found.get();
    }
}