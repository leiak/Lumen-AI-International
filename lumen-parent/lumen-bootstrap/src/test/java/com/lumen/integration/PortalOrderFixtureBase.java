package com.lumen.integration;

import com.lumen.common.api.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5B.4-T4.1 — 客户门户订单 IT 的共享 fixture 基类.
 *
 * <p>6 个 IT ({@code PortalOrderPreviewIT} / {@code PortalOrderCreateIT} /
 * {@code PortalOrderCancelPendingIT} / {@code PortalOrderCancelDispatchedForbiddenIT} /
 * {@code PortalOrderCrossCustomer404IT} / {@code PortalOrderPickupWindowValidationIT})
 * 全部走同一套 fixture: 两个 portal user (alice→customer 42 / carol→customer 99) +
 * 共享 warehouse + 可选的 rate override / billing rule + 预置 DO.
 *
 * <p>容器复用 {@link IntegrationBase} (testcontainers MySQL 8.0 + Redis 7, Flyway 跑完
 * V1..V36), HTTP 走真实 {@code TestRestTemplate} (NoOpResponseErrorHandler, 4xx 不抛).
 *
 * <p><b>租户</b>: portal JWT 的 {@code tid} claim = 1, 但 {@code TenantInterceptor} 只在
 * 请求带 {@code X-Tenant-Id} header 时才设 {@code TenantContext} — 沿用 {@link PortalTrackIT}
 * 记录的 workaround, login 与业务调用都带 {@code X-Tenant-Id: 1}.
 *
 * <p><b>id 段</b>: fixture 主键从 960_001 递增 (避开 PortalAuthIT 950_001..950_006 /
 * PortalTrackIT 950_007+). 业务表 (wms_delivery_order / t_billing_accrual /
 * t_event_outbox) 用 AUTO_INCREMENT, 靠 {@code customer_id} / {@code aggregate_id} 反查清理.
 */
abstract class PortalOrderFixtureBase extends IntegrationBase {

    /** Portal 客户角色 id = 14 (V33 seed). */
    protected static final long CUSTOMER_ROLE_ID = 14L;

    protected static final String ALICE = "alice_order_45b";
    protected static final String ALICE_PWD = "alice-order-pwd";
    protected static final String CAROL = "carol_order_45b";
    protected static final String CAROL_PWD = "carol-order-pwd";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final AtomicLong FIXTURE_ID = new AtomicLong(960_001L);

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected BCryptPasswordEncoder passwordEncoder;

    protected long aliceId;
    protected long carolId;
    protected long customer42Id;
    protected long customer99Id;
    /** DO.warehouse_id NOT NULL 但无 FK 约束到 wms_warehouse, 任意 BIGINT 均可. */
    protected long warehouseId;

    private final Set<Long> userIds = new HashSet<>();
    private final Set<Long> customerIds = new HashSet<>();
    private final Set<Long> linkIds = new HashSet<>();
    private final Set<Long> overrideIds = new HashSet<>();
    private final Set<Long> ruleIds = new HashSet<>();
    private final Set<Long> accrualIds = new HashSet<>();

    @BeforeEach
    void seedPortalFixture() {
        aliceId = nextId();
        seedUser(aliceId, ALICE, ALICE_PWD, "Alice Order");
        bindRole(aliceId);
        customer42Id = nextId();
        seedCustomer(customer42Id, "C042-45B", "Acme Order 客户");
        bindLink(aliceId, customer42Id, 1);

        carolId = nextId();
        seedUser(carolId, CAROL, CAROL_PWD, "Carol Order");
        bindRole(carolId);
        customer99Id = nextId();
        seedCustomer(customer99Id, "C099-45B", "Beta Order 客户");
        bindLink(carolId, customer99Id, 1);

        warehouseId = nextId();
    }

    @AfterEach
    void cleanPortalFixture() {
        try {
            List<Long> doIds = new ArrayList<>();
            for (Long cid : customerIds) {
                doIds.addAll(jdbc.queryForList(
                    "SELECT id FROM wms_delivery_order WHERE customer_id = ?", Long.class, cid));
            }
            deleteByDoIds(doIds);

            // deleteByDoIds 已按 business_id 删过 DO 关联的 accrual; 这里是幂等兜底,
            // 防 fixture 顺序漂移 (seedAccrual 的行没挂在本次 DO 上) 时漏删
            for (Long aid : accrualIds) {
                try { jdbc.update("DELETE FROM t_billing_accrual WHERE id = ?", aid); }
                catch (Exception ignored) {}
            }
            for (Long oid : overrideIds) {
                try { jdbc.update("DELETE FROM t_customer_rate_override WHERE id = ?", oid); }
                catch (Exception ignored) {}
            }
            for (Long rid : ruleIds) {
                try { jdbc.update("DELETE FROM t_billing_rule_item WHERE rule_id = ?", rid); }
                catch (Exception ignored) {}
            }
            for (Long rid : ruleIds) {
                try { jdbc.update("DELETE FROM t_billing_rule WHERE id = ?", rid); }
                catch (Exception ignored) {}
            }
            for (Long cid : customerIds) {
                try { jdbc.update("DELETE FROM wms_delivery_order WHERE customer_id = ?", cid); }
                catch (Exception ignored) {}
            }
            for (Long lid : linkIds) {
                try { jdbc.update("DELETE FROM t_customer_user_link WHERE id = ?", lid); }
                catch (Exception ignored) {}
            }
            for (Long uid : userIds) {
                try { jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", uid); }
                catch (Exception ignored) {}
            }
            for (Long uid : userIds) {
                try { jdbc.update("DELETE FROM sys_user WHERE id = ?", uid); }
                catch (Exception ignored) {}
            }
            for (Long cid : customerIds) {
                try { jdbc.update("DELETE FROM wms_customer WHERE id = ?", cid); }
                catch (Exception ignored) {}
            }
        } finally {
            userIds.clear();
            customerIds.clear();
            linkIds.clear();
            overrideIds.clear();
            ruleIds.clear();
            accrualIds.clear();
        }
    }

    // ==================================================================
    //  fixture helpers
    // ==================================================================

    protected long nextId() {
        return FIXTURE_ID.incrementAndGet();
    }

    protected void seedUser(long id, String username, String rawPassword, String realName) {
        jdbc.update(
            "INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, "
                + "version, deleted, created_at, updated_at) "
                + "VALUES (?, 1, ?, ?, ?, 1, 0, 0, NOW(), NOW())",
            id, username, passwordEncoder.encode(rawPassword), realName);
        userIds.add(id);
    }

    protected void bindRole(long userId) {
        // sys_user_role.id 用 nanoTime 派生, 避免重复跑同一 case 时撞唯一键
        long pk = Math.abs(System.nanoTime()) % 2_000_000_000L;
        try {
            jdbc.update(
                "INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, created_at) "
                    + "VALUES (?, ?, ?, 1, NOW())", pk, userId, CUSTOMER_ROLE_ID);
        } catch (Exception dup) {
            // already bound — 幂等
        }
    }

    protected void seedCustomer(long id, String code, String name) {
        jdbc.update(
            "INSERT INTO wms_customer (id, tenant_id, code, name, deleted, version, created_at, updated_at) "
                + "VALUES (?, 1, ?, ?, 0, 1, NOW(), NOW())", id, code, name);
        customerIds.add(id);
    }

    protected void bindLink(long userId, long customerId, int isPrimary) {
        long pk = Math.abs(System.nanoTime()) % 2_000_000_000L;
        jdbc.update(
            "INSERT INTO t_customer_user_link (id, tenant_id, user_id, customer_id, is_primary, "
                + "deleted, version, created_at, updated_at) VALUES (?, 1, ?, ?, ?, 0, 1, NOW(), NOW())",
            pk, userId, customerId, isPrimary);
        linkIds.add(pk);
    }

    /**
     * 预置一条 {@code wms_delivery_order}.
     *
     * @param status 直接给定状态 (PENDING_DISPATCH / DISPATCHED / …), 用于取消路径的 fixture
     */
    protected long seedDeliveryOrder(long customerId, String status) {
        long id = nextId();
        jdbc.update(
            "INSERT INTO wms_delivery_order "
                + "(id, tenant_id, customer_id, source_no, source_type, no, status, warehouse_id, "
                + " total_weight_kg, total_volume_m3, stop_count, sign_required, "
                + " created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, ?, 'SRC-45B', 'PORTAL', ?, ?, ?, 10.0000, 0.5000, 1, 1, "
                + " NOW(), NOW(), 0, 1)",
            id, customerId, uniqueDoNo(), status, warehouseId);
        return id;
    }

    /**
     * 预置一条带 4.5B 取件窗口的 {@code wms_delivery_order}.
     *
     * <p>{@code seedDeliveryOrder} 不填两列 (留 NULL) — 用这个变体专门验证
     * 读端点把窗口原样透出.
     */
    protected long seedDeliveryOrderWithWindow(long customerId, String status,
                                               LocalDateTime winStart, LocalDateTime winEnd) {
        long id = nextId();
        jdbc.update(
            "INSERT INTO wms_delivery_order "
                + "(id, tenant_id, customer_id, source_no, source_type, no, status, warehouse_id, "
                + " total_weight_kg, total_volume_m3, stop_count, sign_required, "
                + " planned_pickup_window_start, planned_pickup_window_end, "
                + " created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, ?, 'SRC-45B', 'PORTAL', ?, ?, ?, 10.0000, 0.5000, 1, 1, "
                + " ?, ?, NOW(), NOW(), 0, 1)",
            id, customerId, uniqueDoNo(), status, warehouseId, winStart, winEnd);
        return id;
    }

    /**
     * Seed 一条 {@code t_customer_rate_override} 合同价.
     *
     * <p>让 {@code BillingEngineService.estimate} 走 override 短路分支 (优先级高于
     * BillingRule + Aviator), 从而让 preview / estimateAndAccrue 的结果确定 — 不受
     * 其它 IT 遗留的 ACTIVE rule 影响.
     */
    protected long seedRateOverride(long customerId, BigDecimal baseAmount) {
        long id = nextId();
        jdbc.update(
            "INSERT INTO t_customer_rate_override "
                + "(id, tenant_id, customer_id, business_type, base_amount, currency, "
                + " effective_from, effective_to, remark, deleted, created_at, updated_at, version) "
                + "VALUES (?, 1, ?, 'LAST_MILE', ?, 'CNY', ?, NULL, '4.5B IT fixture', 0, NOW(), NOW(), 0)",
            id, customerId, baseAmount, LocalDate.now());
        overrideIds.add(id);
        return id;
    }

    /**
     * Seed 一条 ACTIVE 的 {@code t_billing_rule} (LAST_MILE / RECEIVABLE / CUSTOMER) + 单条
     * {@code t_billing_rule_item}.
     *
     * <p>为什么需要: {@code BillingEngineServiceImpl.accrue} 只走 rule 路径 (不查
     * override), 没有 ACTIVE rule 时会 {@code markNoRule} 并返回 null — 不会写 accrual.
     * priority=1 让本 IT 的 rule 在 {@code ORDER BY priority ASC, id ASC} 里排最前,
     * 保证 accrue 一定能命中.
     */
    protected long seedActiveLastMileRule(String expression) {
        long ruleId = nextId();
        jdbc.update(
            "INSERT INTO t_billing_rule "
                + "(id, tenant_id, business_type, direction, counterparty_type, currency, priority, "
                + " status, effective_from, effective_to, remark, created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, 'LAST_MILE', 'RECEIVABLE', 'CUSTOMER', 'CNY', 1, 'ACTIVE', "
                + " DATE_SUB(CURRENT_DATE, INTERVAL 1 DAY), NULL, '4.5B IT fixture', NOW(3), NOW(3), 0, 0)",
            ruleId);
        jdbc.update(
            "INSERT INTO t_billing_rule_item "
                + "(rule_id, sequence, item_type, expression, expression_hash, result_field, "
                + " created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, 'FORMULA', ?, SHA2(?, 256), 'gross_amount', NOW(3), NOW(3), 0, 0)",
            ruleId, expression, expression);
        ruleIds.add(ruleId);
        return ruleId;
    }

    /**
     * Seed 一条 {@code t_billing_accrual}, 挂到给定的 DO 上.
     *
     * <p>4.5C-1 用: 直接落 OPEN / OPEN_FAILED 两行, 绕过
     * {@code BillingEngineService.accrue} 的规则求值, 让 IT 的断言只依赖
     * "取消 → 释放" 这条链路, 不受 rule fixture 影响.
     *
     * @param status {@code OPEN} / {@code OPEN_FAILED} / {@code CONVERTED} ...
     */
    protected long seedAccrual(long doId, String status) {
        long id = nextId();
        jdbc.update(
            "INSERT INTO t_billing_accrual "
                + "(id, tenant_id, source_event_id, business_type, business_id, rule_id, direction, "
                + " counterparty_id, gross_amount, tax_amount, net_amount, currency, status, "
                + " payload_json, error_msg, created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, ?, 'LAST_MILE', ?, 0, 'RECEIVABLE', ?, 10.00, 0.00, 10.00, 'CNY', "
                + " ?, NULL, '4.5C-1 IT fixture', NOW(3), NOW(3), 0, 0)",
            id, String.valueOf(doId), doId, customer42Id, status);
        accrualIds.add(id);
        return id;
    }

    // ==================================================================
    //  HTTP helpers
    // ==================================================================

    protected String loginAsPortal(String username, String password) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Tenant-Id", "1");
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        ResponseEntity<R> resp = rest.exchange(
            "/api/auth/portal/login", HttpMethod.POST, new HttpEntity<>(body, h), R.class);
        assertThat(resp.getStatusCode().value())
            .as("portal login 应成功 — user=%s", username)
            .isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        String token = (String) data.get("token");
        assertThat(token).as("portal login 应返回非空 token").isNotBlank();
        return token;
    }

    protected HttpHeaders portalHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        // TenantInterceptor workaround — 与 PortalTrackIT 一致
        h.set("X-Tenant-Id", "1");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected ResponseEntity<R> postJson(String path, String body, HttpHeaders h) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), R.class);
    }

    protected ResponseEntity<R> getJson(String path, HttpHeaders h) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), R.class);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> dataOf(ResponseEntity<R> resp) {
        assertThat(resp.getBody()).as("响应体不应为空").isNotNull();
        return (Map<String, Object>) resp.getBody().getData();
    }

    // ==================================================================
    //  request body helpers
    // ==================================================================

    /** 合法取件窗口起点: now + 1 天 (满足 >= now+2h 且 <= now+30d). */
    protected static LocalDateTime validWindowStart() {
        return LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
    }

    protected static String iso(LocalDateTime t) {
        return t.format(ISO);
    }

    /**
     * 构造 {@code POST /api/customer/order} 请求体.
     *
     * @param start 取件窗口起点; null 则用合法默认值
     * @param end   取件窗口终点; null 表示省略该键 (service 兜底成 start+4h)
     */
    protected String createOrderBody(LocalDateTime start, LocalDateTime end) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"warehouseId\":").append(warehouseId).append(',');
        sb.append("\"shipperAddress\":\"上海市 A 号仓\"," );
        sb.append("\"consigneeAddress\":\"北京市 B 号楼\"," );
        sb.append("\"totalWeightKg\":10,");
        sb.append("\"totalVolumeM3\":0.5,");
        sb.append("\"stopCount\":1,");
        sb.append("\"plannedPickupWindowStart\":\"")
          .append(iso(start != null ? start : validWindowStart())).append('"');
        if (end != null) {
            sb.append(",\"plannedPickupWindowEnd\":\"").append(iso(end)).append('"');
        }
        sb.append(",\"remark\":\"4.5B IT fixture\"");
        return sb.append('}').toString();
    }

    protected String cancelOrderBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    // ==================================================================
    //  assertion helpers
    // ==================================================================

    /** outbox 行数 (按 aggregate_id = String.valueOf(doId) 隔离, 不受其它 IT 干扰). */
    protected int outboxCount(long doId, String action) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT payload FROM t_event_outbox "
                + "WHERE aggregate_type = 'WmsDeliveryOrder' AND aggregate_id = ?",
            doId);
        int n = 0;
        for (Map<String, Object> row : rows) {
            Object payload = row.get("payload");
            // MySQL 的 JSON 列读回来是带空格的 pretty 形态 ("action": "create"),
            // 不是写入前的紧凑形态 — 与 OutboxDeliveryEventIT 的断言同款.
            if (payload != null && String.valueOf(payload).contains("\"action\": \"" + action + "\"")) {
                n++;
            }
        }
        return n;
    }

    protected int accrualCount(long doId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM t_billing_accrual WHERE source_event_id = ? AND business_id = ?",
            Integer.class, String.valueOf(doId), doId);
        return n == null ? 0 : n;
    }

    protected String statusOf(long doId) {
        return jdbc.queryForObject(
            "SELECT status FROM wms_delivery_order WHERE id = ?", String.class, doId);
    }

    private void deleteByDoIds(Collection<Long> doIds) {
        if (doIds.isEmpty()) {
            return;
        }
        for (Long id : doIds) {
            try {
                jdbc.update("DELETE FROM t_billing_accrual WHERE business_id = ?", id);
                jdbc.update("DELETE FROM t_billing_event_log WHERE source_event_id = ?", String.valueOf(id));
                jdbc.update("DELETE FROM t_event_outbox WHERE aggregate_id = ?", String.valueOf(id));
            } catch (Exception ignored) {
                // 表可能尚未建 / 列不存在 — 忽略, teardown 不应让测试失败
            }
        }
    }

    private String uniqueDoNo() {
        return "PO-IT-" + Math.abs(System.nanoTime() % 100_000_000L);
    }
}