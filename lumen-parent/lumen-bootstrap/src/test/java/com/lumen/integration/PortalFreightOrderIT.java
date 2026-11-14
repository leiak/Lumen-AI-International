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

import java.util.ArrayList;
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
 *
 * <p><b>teardown 的清理失败会让用例变红</b> —— 每条清理语句仍然**互相独立**执行
 * (一条失败不阻断后面 7 条), 但失败不再被 {@code System.out.println} 吞掉:
 * 失败会被收集进 {@link #cleanupFailures}, 由 {@link #cleanPortal()} 末尾统一断言。
 * 旧实现吞异常 → **出错的清理语句**看不见, suite 汇总却照样是
 * {@code Tests run: 17, Failures: 0} —— 那正是「假绿」。
 * <p>(那个 **17** 是**当时那 5 个 IT** 的合计: {@code OrderLifecycleIT} 3 +
 * {@code OrderStateMachineSeedIT} 3 + {@code OrderNumberingIT} 2 +
 * {@code OrderBillingAccrualIT} 2 + 本类 7 —— **不含 {@code OrderSchemaIT} 的 8 条**;
 * 6 个 IT 全跑是 25。)
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

    /**
     * teardown 里收集到的清理失败 (每条 = 错误消息 + SQL)。
     *
     * <p>旧实现把异常**吞掉**只 {@code System.out.println} —— 于是「**哪一条清理语句失败了**」
     * 这件事在 suite 汇总里完全看不见: **failsafe** 照样报 {@code Tests run: 17, Failures: 0}
     * （**那 5 个 IT** 的合计, 不含 {@code OrderSchemaIT}; 6 个全跑是 25）。
     * 报这条汇总的**不是 surefire 而是 failsafe** —— {@code lumen-bootstrap/pom.xml} 只配了
     * {@code maven-failsafe-plugin}, surefire 默认的 include 不匹配 {@code *IT.java},
     * 所以 {@code mvn test -Dit.test=X} 会跑 **0 个用例 = 假绿** ——
     * IT 必须走 {@code mvn -pl lumen-bootstrap verify -Dit.test=}。
     * 现在失败会被收集, 由 {@link #cleanPortal()} 末尾统一断言 → 用例变红。
     */
    private final List<String> cleanupFailures = new ArrayList<>();

    @AfterEach
    void cleanPortal() {
        // 每条语句**独立**兜底 —— 一条失败不能连累后面 7 条。
        // (旧实现的 8 条语句本来就是**各自独立**的 runQuietly 调用 —— 只有**带 CAST 的那条
        //  outbox 清理**抛 1267 被 catch 吞掉, **其余 7 条都是成功的** → 数据残留在
        //  outbox 孤儿行上)
        //
        // 「独立执行」≠「静默失败」: 失败照样被收集进 cleanupFailures, 最后统一断言。
        cleanupFailures.clear();

        // 本用例产生的 order id (数值列表) —— outbox 清理靠它做**参数化** IN, 理由见下。
        // 查 id 这一步自己也会失败 → 收进 cleanupFailures, **不中断**后面 8 条语句
        // (跟「每条语句独立」的约定一致: 一条失败不连累后面 7 条)。
        List<Long> orderIds = listOrderIds();

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
        //
        // ⚠ 这条语句**不能**"简化"回 `aggregate_id IN (SELECT CAST(id AS CHAR) FROM t_order ...)`:
        //   t_event_outbox.aggregate_id 是 VARCHAR(64), 表 collation = utf8mb4_unicode_ci;
        //   而 CAST(id AS CHAR) 是**表达式** —— 它带的 collation 是**连接** collation
        //   (MySQL 8 默认 utf8mb4_0900_ai_ci, 与建表的 --collation-server 无关)。
        //   「列 vs 表达式」比较时两边的 collation 都是 IMPLICIT 且互不相同 →
        //   MySQL 直接报 error 1267 Illegal mix of collations, 这条 DELETE 从来没成功过。
        //   改成「Java 侧查出 order id + 参数化 IN (?, ?, ...)」: 参数走的是**字面量**语义,
        //   比较时采用**列的** collation, 不存在「列 vs 表达式」的冲突 ——
        //   与 OrderAdminFixtureBase / PortalOrderFixtureBase 的 aggregate_id = ? 写法一致。
        if (!orderIds.isEmpty()) {
            runQuietly("DELETE FROM t_event_outbox WHERE aggregate_type = 'ORDER' AND aggregate_id IN ("
                    + placeholders(orderIds.size()) + ")",
                    orderIds.stream().map(String::valueOf).toArray());
        }
        runQuietly("DELETE FROM t_order WHERE tenant_id = 1 AND customer_id IN (?, ?)",
                customerAliceId, customerCarolId);
        runQuietly("DELETE FROM t_customer_user_link WHERE tenant_id = 1 AND user_id IN (?, ?)",
                aliceId, carolId);
        runQuietly("DELETE FROM sys_user_role WHERE tenant_id = 1 AND user_id IN (?, ?)", aliceId, carolId);
        runQuietly("DELETE FROM sys_user WHERE tenant_id = 1 AND id IN (?, ?)", aliceId, carolId);
        runQuietly("DELETE FROM wms_customer WHERE tenant_id = 1 AND id IN (?, ?)",
                customerAliceId, customerCarolId);

        // 8 条语句跑完 → 断言一条都没失败。
        // 「每条独立执行」的行为不变 (一条失败不阻断后面 7 条), 只是失败不再被吞掉:
        // 清理失败 = 数据残留 = 下一轮用例被污染 = 必须让**本用例**变红。
        assertThat(cleanupFailures)
                .as("teardown 清理失败 —— 数据会残留到后续用例, 视为用例失败")
                .isEmpty();
    }

    /**
     * teardown 里的单条 SQL —— 失败**收集**起来 (不打断后面的语句), 最后统一断言。
     *
     * <p>与旧实现的区别: 旧实现只打印就吞掉 → 清理失败对 suite 汇总**完全不可见**;
     * 现在失败会进 {@link #cleanupFailures}, 由 {@link #cleanPortal()} 末尾断言 → 用例变红。
     */
    private void runQuietly(String sql, Object... args) {
        try {
            jdbc.update(sql, args);
        } catch (Exception e) {
            String detail = "[teardown] 清理失败: " + e.getMessage() + " | sql=" + sql;
            System.out.println(detail);
            cleanupFailures.add(detail);
        }
    }

    /**
     * 本用例产生的 order id (数值列表)。
     *
     * <p>outbox 清理需要把 order id 转成字符串跟 {@code t_event_outbox.aggregate_id} 比,
     * 但**转换必须在 Java 侧做** —— 见 {@link #cleanPortal()} 里 outbox 那条语句的注释:
     * 在 SQL 里写 {@code CAST(id AS CHAR)} 会引入「列 vs 表达式」的 collation 冲突 (1267)。
     *
     * <p>查 id 失败时不抛 —— 收进 {@link #cleanupFailures} 并返回空列表,
     * 让后面 7 条语句照常执行 (「每条语句独立」的约定), 最后由断言把用例判红。
     */
    private List<Long> listOrderIds() {
        try {
            return jdbc.queryForList(
                    "SELECT id FROM t_order WHERE tenant_id = 1 AND customer_id IN (?, ?)",
                    Long.class, customerAliceId, customerCarolId);
        } catch (Exception e) {
            String detail = "[teardown] 清理失败: " + e.getMessage()
                    + " | sql=SELECT id FROM t_order WHERE tenant_id = 1 AND customer_id IN (?, ?)";
            System.out.println(detail);
            cleanupFailures.add(detail);
            return List.of();
        }
    }

    /** 生成 {@code ?, ?, ?} 这样的占位符串 (长度 = 个数)。 */
    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
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
