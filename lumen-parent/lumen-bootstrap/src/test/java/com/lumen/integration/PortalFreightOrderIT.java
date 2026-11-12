package com.lumen.integration;

import com.lumen.common.api.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.6 — 门户端货运委托单 IT.
 *
 * <p>5 个端点全走一遍: 建单 → 我的列表 → 详情 → 确认 → 取消,
 * 外加两条越权防线: 跨客户访问 404 / 空 reason 400。
 *
 * <p><b>门户可见契约也在这 IT 里钉死</b> —— 已确认的单客户撤不了 (409_101),
 * 且详情不下发任何下一态 ({@code allowedNextStates} 为空数组)。
 *
 * <p><b>不直接复用 {@link PortalOrderFixtureBase}</b> —— 那个基类是为了城配配送单
 * (wms_delivery_order) 设计的, 本 IT 用的是 t_order, 表和清理逻辑都不同。
 *
 * <p><b>租户</b>: 门户 JWT 的 tid=1, 但仍带 {@code X-Tenant-Id: 1}
 * (与 {@link PortalOrderFixtureBase} 一致, 少一个变量少一个坑)。
 *
 * <p><b>必须 seed {@code t_customer_user_link} —— 否则门户登录直接 401</b>。
 * {@code PortalAuthService.login} 的流程是: 查用户 → 校验密码 →
 * {@code hasCustomerRole} (要求绑了 code='customer' 的角色) →
 * {@code listCustomerIdsForUser} (读 {@code t_customer_user_link}) →
 * <b>关联数为 0 就抛 {@code AuthException(PORTAL_NO_CUSTOMER_LINK)}</b> →
 * {@code GlobalExceptionHandler.handleAuth} 把 {@code AuthenticationException}
 * 一律翻译成 <b>HTTP 401</b> (body.code = 401 = {@code CommonErrorCode.UNAUTHORIZED};
 * 异常常量自己的 403_003 在这条路径上被丢弃)。
 * 关联数 &gt; 1 时不返 token (返 choices 让前端选客户), 只有<b>恰好 1 条</b>关联
 * 才会直接发 token。所以本 IT 给 alice / carol <b>各绑恰好一条</b>关联
 * ({@code is_primary = 1}), 两个门户用户各对应一个客户。
 *
 * <p><b>id 段</b>: 971_001 起 —— 与管理端基类的 970_001 段分开
 * ({@code PortalOrderFixtureBase} 960_001 / {@code OutboundShippedCustomerIdIT} 与
 * {@code PortalMerchantBillingFixture} 970_001 —— 后两者写的表与本 IT 无交集)。
 *
 * <p><b>用户名按用例唯一</b>: {@code sys_user} 的唯一键是
 * {@code uk_user_tenant_username (tenant_id, username)} (不带 deleted) ——
 * 用户名里带上用户 id, 用例之间就不再互相依赖 teardown 的成败。
 */
@DisplayName("PortalFreightOrder IT")
class PortalFreightOrderIT extends IntegrationBase {

    /** Portal 客户角色 id = 14 (V33 seed, code='customer')。 */
    private static final long CUSTOMER_ROLE_ID = 14L;
    private static final String ALICE_PWD = "alice-order46-pwd";
    private static final String CAROL_PWD = "carol-order46-pwd";

    private static final AtomicLong FIXTURE_ID = new AtomicLong(971_001L);

    @Autowired JdbcTemplate jdbc;
    @Autowired BCryptPasswordEncoder passwordEncoder;

    private long aliceId;
    private long carolId;
    private long customerAliceId;
    private long customerCarolId;
    private String aliceToken;
    private String carolToken;

    @BeforeEach
    void seedPortal() {
        aliceId = nextId();
        seedUser(aliceId, "alice_order_46_" + aliceId, ALICE_PWD);
        bindRole(aliceId);
        customerAliceId = nextId();
        seedCustomer(customerAliceId, "C046-ALICE-" + customerAliceId, "4.6 门户 Alice 客户");
        // 关键: 绑 customer 关联, 否则门户登录 401 (PORTAL_NO_CUSTOMER_LINK)
        bindLink(aliceId, customerAliceId);

        carolId = nextId();
        seedUser(carolId, "carol_order_46_" + carolId, CAROL_PWD);
        bindRole(carolId);
        customerCarolId = nextId();
        seedCustomer(customerCarolId, "C046-CAROL-" + customerCarolId, "4.6 门户 Carol 客户");
        bindLink(carolId, customerCarolId);

        aliceToken = login("alice_order_46_" + aliceId, ALICE_PWD);
        carolToken = login("carol_order_46_" + carolId, CAROL_PWD);
    }

    @AfterEach
    void cleanPortal() {
        // 每条语句**独立**兜底 —— 一条失败不能连累后面 7 条。
        // (旧写法把 8 条塞进同一个 try: 第 1 条一抛异常, 后面 7 条全被 catch 吞掉, 数据残留)
        //
        // t_order_cargo **没有 customer_id 列** —— 货物明细只认 order_id,
        // 所以必须走 t_order 的子查询, 不能照抄 t_order 的过滤条件。
        // 子查询也带 tenant_id —— 外层带了内层不带, 过滤口径自相矛盾。
        runQuietly("DELETE FROM t_order_cargo WHERE tenant_id = 1 AND order_id IN "
                + "(SELECT id FROM t_order WHERE tenant_id = 1 AND customer_id IN (?, ?))",
                customerAliceId, customerCarolId);
        runQuietly("DELETE FROM t_order_service_item WHERE tenant_id = 1 AND order_id IN "
                + "(SELECT id FROM t_order WHERE tenant_id = 1 AND customer_id IN (?, ?))",
                customerAliceId, customerCarolId);
        // outbox 按 aggregate_id 精确删 —— 不能写成 "aggregate_type='ORDER'" 一把梭:
        // 那会连带删掉本租户其它用例产生的 ORDER 事件, 且 t_event_outbox.tenant_id 允许 NULL
        // (跨租户事件留 NULL), 带 tenant_id=1 会漏删。
        runQuietly("DELETE FROM t_event_outbox WHERE aggregate_type = 'ORDER' AND aggregate_id IN "
                + "(SELECT CAST(id AS CHAR) FROM t_order WHERE tenant_id = 1 AND customer_id IN (?, ?))",
                customerAliceId, customerCarolId);
        runQuietly("DELETE FROM t_order WHERE tenant_id = 1 AND customer_id IN (?, ?)",
                customerAliceId, customerCarolId);
        runQuietly("DELETE FROM t_customer_user_link WHERE tenant_id = 1 AND user_id IN (?, ?)",
                aliceId, carolId);
        runQuietly("DELETE FROM sys_user_role WHERE tenant_id = 1 AND user_id IN (?, ?)", aliceId, carolId);
        runQuietly("DELETE FROM sys_user WHERE tenant_id = 1 AND id IN (?, ?)", aliceId, carolId);
        runQuietly("DELETE FROM wms_customer WHERE tenant_id = 1 AND id IN (?, ?)",
                customerAliceId, customerCarolId);
    }

    /** teardown 里的单条 SQL —— 失败只记日志, 不让测试因为清理失败而红。 */
    private void runQuietly(String sql, Object... args) {
        try {
            jdbc.update(sql, args);
        } catch (Exception e) {
            System.out.println("[teardown] 清理失败(已忽略): " + e.getMessage() + " | sql=" + sql);
        }
    }

    // ================================================================
    //  用例
    // ================================================================

    @Test
    @DisplayName("建单 → 我的列表 → 详情: 归属正确")
    void createListDetail_works() {
        HttpHeaders h = portalHeaders(aliceToken);

        ResponseEntity<R> create = postJson("/api/customer/freight-order", createBody(), h);
        assertThat(create.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> created = dataOf(create);
        Long id = ((Number) created.get("id")).longValue();
        assertThat(created.get("orderStatus")).isEqualTo("DRAFT");
        // 归属取自门户 token 的 active_customer_id claim —— 客户端 body 里没有 customerId 可传
        assertThat(((Number) created.get("customerId")).longValue()).isEqualTo(customerAliceId);

        // 我的列表 —— 只应有自己建的单
        ResponseEntity<R> list = getJson("/api/customer/freight-order?current=1&size=20", h);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        List<?> records = (List<?>) dataOf(list).get("records");
        assertThat(records).hasSize(1);
        assertThat(((Number) ((Map<?, ?>) records.get(0)).get("id")).longValue()).isEqualTo(id);

        // 详情
        ResponseEntity<R> detail = getJson("/api/customer/freight-order/" + id, h);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> d = dataOf(detail);
        assertThat(d.get("orderNo")).isEqualTo(created.get("orderNo"));
        assertThat((List<?>) d.get("cargo")).hasSize(1);
    }

    @Test
    @DisplayName("跨客户访问详情 → 404 (不泄露存在性)")
    void crossCustomerDetail_returns404() {
        HttpHeaders h = portalHeaders(aliceToken);
        ResponseEntity<R> create = postJson("/api/customer/freight-order", createBody(), h);
        Long id = ((Number) dataOf(create).get("id")).longValue();

        // Carol 访问 Alice 的单 —— 必须 404, 不是 403
        ResponseEntity<R> resp = getJson("/api/customer/freight-order/" + id, portalHeaders(carolToken));
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("客户确认: PENDING_CONFIRM → CONFIRMED, 且内部成本字段被抹掉")
    void confirm_movesToConfirmed() {
        Long id = seedOrder(customerAliceId, "PENDING_CONFIRM");

        ResponseEntity<R> resp = postJson("/api/customer/freight-order/" + id + "/confirm", null,
                portalHeaders(aliceToken));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");

        // 读出侧的内部成本裁剪: 库里那行服务项的 supplier_id / cost_amount 是有值的,
        // 门户响应里必须被抹成 null (Task 7 的 stripInternalCost)。
        List<?> items = (List<?>) dataOf(resp).get("serviceItems");
        assertThat(items).hasSize(1);
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertThat(item.get("supplierId")).as("门户不能看到我方供应商").isNull();
        assertThat(item.get("costAmount")).as("门户不能看到我方成本").isNull();
    }

    @Test
    @DisplayName("客户取消: PENDING_CONFIRM → CANCELLED")
    void cancel_movesToCancelled() {
        Long id = seedOrder(customerAliceId, "PENDING_CONFIRM");

        ResponseEntity<R> resp = postJson("/api/customer/freight-order/" + id + "/cancel",
                "{\"reason\":\"客户临时取消这批货\"}", portalHeaders(aliceToken));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("取消原因为空 → 400 (code 400_103)")
    void cancel_blankReason_returns400() {
        Long id = seedOrder(customerAliceId, "PENDING_CONFIRM");

        ResponseEntity<R> resp = postJson("/api/customer/freight-order/" + id + "/cancel",
                "{\"reason\":\"  \"}", portalHeaders(aliceToken));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(400_103);
    }

    /**
     * <b>门户撤单的状态闸</b> —— 已确认 ({@code CONFIRMED}) 的单客户撤不了。
     *
     * <p>V42 里 {@code CONFIRMED → CANCELLED} 的 guard 是 {@code role:order_supervisor}
     * —— 那是**我方主管**的撤单动作 (合同已成立), 门户端不开放。
     * 客户撞到的是 {@code OrderErrorCode.STATE_TRANSITION_INVALID} = 409_101 / HTTP 409。
     */
    @Test
    @DisplayName("取消已确认的单 → 409 (code 409_101, 客户无权自助撤单)")
    void cancel_confirmedOrder_returns409() {
        Long id = seedOrder(customerAliceId, "CONFIRMED");

        ResponseEntity<R> resp = postJson("/api/customer/freight-order/" + id + "/cancel",
                "{\"reason\":\"客户临时取消这批货\"}", portalHeaders(aliceToken));

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(409_101);
        // 状态没被动过 —— 409 是「拒」不是「撤」
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");
    }

    /**
     * <b>门户详情只下发客户点得到的下一态</b> —— 已确认的单一张按钮都不该下发。
     *
     * <p>管理端那张整张状态机的出边 (含 {@code CANCELLED} / {@code VOID} / {@code IN_OPERATION})
     * 直接返给客户, 等于把「取消委托」按钮画在一张客户撤不掉的单上。
     */
    @Test
    @DisplayName("已确认的单: 详情 allowedNextStates 为空数组")
    void detail_confirmedOrder_hasNoAllowedNextStates() {
        Long id = seedOrder(customerAliceId, "CONFIRMED");

        ResponseEntity<R> resp = getJson("/api/customer/freight-order/" + id, portalHeaders(aliceToken));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<?> allowed = (List<?>) dataOf(resp).get("allowedNextStates");
        assertThat(allowed).as("已确认的单不应下发任何下一态").isNotNull().isEmpty();
    }

    // ================================================================
    //  helpers
    // ================================================================

    private long nextId() {
        return FIXTURE_ID.incrementAndGet();
    }

    private void seedUser(long id, String username, String rawPassword) {
        jdbc.update(
            "INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, "
                + "version, deleted, created_at, updated_at) "
                + "VALUES (?, 1, ?, ?, ?, 1, 0, 0, NOW(), NOW())",
            id, username, passwordEncoder.encode(rawPassword), username);
    }

    private void bindRole(long userId) {
        long pk = Math.abs(System.nanoTime()) % 2_000_000_000L;
        try {
            jdbc.update(
                "INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, created_at) "
                    + "VALUES (?, ?, ?, 1, NOW())", pk, userId, CUSTOMER_ROLE_ID);
        } catch (Exception dup) {
            // already bound
        }
    }

    /**
     * 绑客户关联 —— <b>门户登录的前提</b>。
     *
     * <p>{@code PortalAuthService.login} 靠它决定「能不能拿到 token」:
     * 0 条关联 → 抛 {@code AuthException(PORTAL_NO_CUSTOMER_LINK)} → HTTP 401;
     * &gt; 1 条 → 不发 token, 返 choices;
     * 恰好 1 条 → 直接发 token。所以这里<b>一个用户只绑一条</b>, is_primary=1。
     */
    private void bindLink(long userId, long customerId) {
        long pk = Math.abs(System.nanoTime()) % 2_000_000_000L;
        jdbc.update(
            "INSERT INTO t_customer_user_link (id, tenant_id, user_id, customer_id, is_primary, "
                + "deleted, version, created_at, updated_at) VALUES (?, 1, ?, ?, ?, 0, 1, NOW(), NOW())",
            pk, userId, customerId, 1);
    }

    private void seedCustomer(long id, String code, String name) {
        jdbc.update(
            "INSERT INTO wms_customer (id, tenant_id, code, name, deleted, version, created_at, updated_at) "
                + "VALUES (?, 1, ?, ?, 0, 1, NOW(), NOW())", id, code, name);
    }

    /**
     * 预置一条指定状态的委托单 (含 1 条货物 + 1 条服务项)。
     *
     * <p>服务项上带 {@code supplier_id=999} / {@code cost_amount=800} ——
     * 让「门户读出侧抹掉内部成本」这条断言有东西可抹 (库里确实有值)。
     * 金额 amount=1000 让 confirm 的 {@code requirePositiveAmount} 守卫能过。
     */
    private Long seedOrder(long customerId, String status) {
        long id = nextId();
        jdbc.update(
            "INSERT INTO t_order (id, tenant_id, order_no, business_type, customer_id, trade_direction, "
                + " incoterms, service_type, order_status, settlement_currency, total_qty, total_weight_kg, "
                + " total_volume_cbm, is_dangerous, is_oversized, created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, ?, 'OCEAN_FCL', ?, 'EXPORT', 'FOB', 'D2D', ?, 'CNY', 10, 100, 12.5, "
                + " 0, 0, NOW(), NOW(), 0, 1)",
            id, "ORD-IT-" + id, customerId, status);
        jdbc.update(
            "INSERT INTO t_order_cargo (id, tenant_id, order_id, cargo_name_cn, pkg_type, pkg_qty, unit, "
                + " weight_kg, volume_cbm, is_stackable, is_dangerous, created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, ?, '钢材', 'CARTON', 10, 'CTN', 100, 12.5, 1, 0, NOW(), NOW(), 0, 1)",
            nextId(), id);
        jdbc.update(
            "INSERT INTO t_order_service_item (id, tenant_id, order_id, fee_type, fee_name, fee_category, "
                + " calc_basis, qty, unit_price, amount, currency_code, supplier_id, cost_amount, "
                + " is_mandatory, source, created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, ?, 'OCEAN_FREIGHT', '海运费', 'FREIGHT', 'PER_CONTAINER', 1, 1000, 1000, "
                + " 'CNY', 999, 800, 1, 'MANUAL', NOW(), NOW(), 0, 1)",
            nextId(), id);
        return id;
    }

    private String statusOf(long orderId) {
        return jdbc.queryForObject("SELECT order_status FROM t_order WHERE id = ?", String.class, orderId);
    }

    private String login(String username, String password) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Tenant-Id", "1");
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        ResponseEntity<R> resp = rest.exchange(
                "/api/auth/portal/login", HttpMethod.POST, new HttpEntity<>(body, h), R.class);
        assertThat(resp.getStatusCode().value()).as("portal login 应 200 — user=%s", username).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        String token = (String) data.get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    private HttpHeaders portalHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Tenant-Id", "1");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<R> postJson(String path, String body, HttpHeaders h) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), R.class);
    }

    private ResponseEntity<R> getJson(String path, HttpHeaders h) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), R.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseEntity<R> resp) {
        assertThat(resp.getBody()).isNotNull();
        return (Map<String, Object>) resp.getBody().getData();
    }

    private String createBody() {
        return "{"
                + "\"businessType\":\"OCEAN_FCL\","
                + "\"tradeDirection\":\"EXPORT\","
                + "\"incoterms\":\"FOB\","
                + "\"serviceType\":\"D2D\","
                + "\"originAddress\":\"上海洋山港\","
                + "\"destAddress\":\"洛杉矶港\","
                + "\"settlementCurrency\":\"CNY\","
                + "\"remark\":\"4.6 门户 IT\","
                + "\"cargo\":[{\"cargoNameCn\":\"钢材\",\"pkgType\":\"CARTON\",\"pkgQty\":10,"
                + "\"unit\":\"CTN\",\"weightKg\":100,\"volumeCbm\":12.5}],"
                + "\"serviceItems\":[{\"feeType\":\"OCEAN_FREIGHT\",\"feeName\":\"海运费\","
                + "\"calcBasis\":\"PER_CONTAINER\",\"qty\":1,\"unitPrice\":1000,\"amount\":1000}]"
                + "}";
    }
}
