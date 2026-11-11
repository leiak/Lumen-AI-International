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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.6 — 货运委托单管理端 IT 的共享 fixture.
 *
 * <p>4 个管理端 IT ({@code OrderLifecycleIT} / {@code OrderStateMachineSeedIT} /
 * {@code OrderNumberingIT} / {@code OrderBillingAccrualIT}) 共用:
 * 一个绑了 role 1 (SUPER_ADMIN) 的 admin 用户 + 一个客户 + 建单 body 构造器.
 *
 * <p><b>为什么用 role 1</b>: V42 把 10 个 {@code order:*} 权限显式绑给了 role 1
 * (id 334..343)。{@code sys_role_permission} 不是「SUPER_ADMIN 自动拥有全部权限」,
 * 靠 seed 显式补行 —— 用别的角色就得多绑一批权限, 与本 IT 要验的东西无关。
 *
 * <p><b>id 段</b>: fixture 主键从 970_001 递增。已逐个核过 bootstrap 既有 IT 的
 * 主键段 —— {@code PortalAuthIT} 950_000 / {@code PortalTrackIT} 950_007 /
 * {@code PortalProfileIT} 950_200 / {@code PortalOrderFixtureBase} 960_001 /
 * {@code CsCrossModuleIT} 900_001 (sys_user) / {@code BillingPermissionIT} 901_001+,
 * 均不与本段相撞。
 *
 * <p><b>970_001 段另有 {@code OutboundShippedCustomerIdIT} 与
 * {@code PortalMerchantBillingFixture} 在用</b> —— 逐个核过它们写哪些表:
 * 前者<b>一条 INSERT 都没有</b> (只调 HTTP 接口 + 删 {@code wms_delivery_order} /
 * {@code wms_delivery_stop}); 后者只往 {@code t_billing_adjustment} /
 * {@code t_billing_invoice} / {@code t_reconciliation} / {@code t_payment_record} /
 * {@code t_monthly_closing} 写。与本基类写的 {@code sys_user} / {@code wms_customer} /
 * {@code t_order} / {@code t_billing_rule} / {@code t_billing_rule_item} 无交集 ——
 * 主键唯一性是<b>按表</b>的, 跨表同号无妨。
 *
 * <p><b>用户名 / 客户编码按用例唯一</b>: {@code sys_user} 的唯一键是
 * {@code uk_user_tenant_username (tenant_id, username)}, {@code wms_customer} 的唯一键是
 * {@code uk_wms_customer_code (tenant_id, code)} —— 两者都不带 {@code deleted}。
 * 若每条用例都用同一个固定值, 一旦某条用例的 teardown 删失败, 下一条用例的 seed
 * 就会撞唯一键, 报出一个与真实原因完全无关的错误 (「Duplicate entry」),
 * 把排查方向带偏。所以这里把 id 拼进用户名与客户编码 —— 每条用例天然不撞,
 * 用例之间不再互相依赖 teardown 的成败。
 *
 * <p><b>teardown 每条语句独立兜底 + 记日志</b>: 一条失败不能连累后面的语句;
 * 静默吞异常会让「上一步没删干净」变成下一条用例看不懂的唯一键冲突。
 */
abstract class OrderAdminFixtureBase extends IntegrationBase {

    protected static final long TEST_TENANT = 1L;
    /** SUPER_ADMIN (V2 seed: id 1, code='SUPER_ADMIN', tenant 1). */
    protected static final long SUPER_ADMIN_ROLE_ID = 1L;

    /** admin 用户名前缀 —— 实际用户名 = 前缀 + 本条用例的 adminUserId。 */
    protected static final String ADMIN_USER_PREFIX = "order_admin_46_";
    protected static final String ADMIN_PWD = "order-admin-pwd-2026";
    /** 客户编码前缀 —— 实际编码 = 前缀 + 本条用例的 customerId。 */
    protected static final String CUSTOMER_CODE_PREFIX = "C046-ORD-";
    protected static final String CUSTOMER_NAME = "4.6 委托单 IT 客户";

    private static final AtomicLong FIXTURE_ID = new AtomicLong(970_001L);

    /** 已 seed 的 ORDER 计费规则条数 —— 用来给每条规则错开 effective_from。 */
    private static final AtomicLong RULE_SEQ = new AtomicLong(0L);

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected BCryptPasswordEncoder passwordEncoder;

    protected long adminUserId;
    /** 本条用例的 admin 用户名 (登录时用)。 */
    protected String adminUsername;
    protected long customerId;
    /** 本条用例的客户编码。 */
    protected String customerCode;

    private final Set<Long> orderIds = new HashSet<>();
    private final Set<Long> userIds = new HashSet<>();
    private final List<Long> ruleIds = new ArrayList<>();

    @BeforeEach
    void seedOrderFixture() {
        adminUserId = nextId();
        adminUsername = ADMIN_USER_PREFIX + adminUserId;
        jdbc.update(
            "INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, "
                + "version, deleted, created_at, updated_at) "
                + "VALUES (?, 1, ?, ?, '4.6 IT admin', 1, 0, 0, NOW(), NOW())",
            adminUserId, adminUsername, passwordEncoder.encode(ADMIN_PWD));
        userIds.add(adminUserId);
        bindRole(adminUserId, SUPER_ADMIN_ROLE_ID);

        customerId = nextId();
        customerCode = CUSTOMER_CODE_PREFIX + customerId;
        jdbc.update(
            "INSERT INTO wms_customer (id, tenant_id, code, name, deleted, version, created_at, updated_at) "
                + "VALUES (?, 1, ?, ?, 0, 1, NOW(), NOW())",
            customerId, customerCode, CUSTOMER_NAME);
    }

    @AfterEach
    void cleanOrderFixture() {
        try {
            for (Long oid : orderIds) {
                runQuietly("DELETE FROM t_order_cargo WHERE order_id = ?", oid);
                runQuietly("DELETE FROM t_order_service_item WHERE order_id = ?", oid);
                // outbox 按 aggregate_id 精确删 —— 不能写成 aggregate_type='ORDER' 一把梭,
                // 那会连带删掉本租户其它用例产生的 ORDER 事件。
                runQuietly("DELETE FROM t_event_outbox WHERE aggregate_type = 'ORDER' AND aggregate_id = ?",
                        String.valueOf(oid));
                runQuietly("DELETE FROM t_billing_accrual WHERE business_type = 'ORDER' AND business_id = ?", oid);
                runQuietly("DELETE FROM t_billing_event_log WHERE business_type = 'ORDER' AND business_id = ?", oid);
                runQuietly("DELETE FROM t_order WHERE id = ?", oid);
            }
            for (Long rid : ruleIds) {
                // 必须先删 item 再删 rule —— t_billing_rule_item 有 FK fk_ri_rule → t_billing_rule(id)。
                runQuietly("DELETE FROM t_billing_rule_item WHERE rule_id = ?", rid);
                runQuietly("DELETE FROM t_billing_rule WHERE id = ?", rid);
            }
            for (Long uid : userIds) {
                runQuietly("DELETE FROM sys_user_role WHERE user_id = ?", uid);
                runQuietly("DELETE FROM sys_user WHERE id = ?", uid);
            }
            runQuietly("DELETE FROM wms_customer WHERE id = ?", customerId);
        } finally {
            orderIds.clear();
            userIds.clear();
            ruleIds.clear();
        }
    }

    // ==================================================================
    //  fixture helpers
    // ==================================================================

    protected long nextId() {
        return FIXTURE_ID.incrementAndGet();
    }

    /**
     * teardown 里的单条 SQL —— 失败只记日志, 不让测试因为清理失败而红。
     *
     * <p>不静默吞异常: 清理失败必须留痕, 否则下一条用例报出来的是
     * 「唯一键冲突」这种与真实原因无关的错误, 排查方向会被带偏。
     */
    protected void runQuietly(String sql, Object... args) {
        try {
            jdbc.update(sql, args);
        } catch (Exception e) {
            System.out.println("[teardown] 清理失败(已忽略): " + e.getMessage()
                    + " | sql=" + sql);
        }
    }

    protected void bindRole(long userId, long roleId) {
        long pk = Math.abs(System.nanoTime()) % 2_000_000_000L;
        try {
            jdbc.update(
                "INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, created_at) "
                    + "VALUES (?, ?, ?, 1, NOW())", pk, userId, roleId);
        } catch (Exception dup) {
            // 已绑过 —— 幂等
        }
    }

    /** 记下一个本 IT 创建的订单 id, 供 teardown 精确清理。 */
    protected void trackOrder(long id) {
        orderIds.add(id);
    }

    /**
     * Seed 一条 ACTIVE 的 {@code t_billing_rule} (business_type='ORDER') + 单条 FORMULA item.
     *
     * <p>{@code BillingEngineServiceImpl.accrue} 只走 rule 路径, 没有 ACTIVE rule 时会
     * markNoRule 并返回 null —— 不写 accrual。priority=1 让本 IT 的 rule 在
     * {@code ORDER BY priority ASC, id ASC} 里排最前, 保证 accrue 一定能命中。
     *
     * <p><b>effective_from 每条规则错开一天</b>: {@code t_billing_rule} 的唯一键是
     * {@code uk_rule (tenant_id, business_type, direction, counterparty_type, effective_from, deleted)}。
     * 若固定用 {@code DATE_SUB(CURRENT_DATE, INTERVAL 1 DAY)}, 同一天内 seed 的两条规则
     * (本 IT 类有 2 条用例各 seed 一次) 的 effective_from 完全相同 —— 只要上一条用例的
     * teardown 没删干净, 第二条就会撞唯一键, 报一个与「清理没删干净」毫无关系的错。
     * 错开天数后两条规则的 effective_from 天然不同, 撞键这条路直接消失;
     * 万一两份规则同时存在, accrue 按 {@code priority ASC, id ASC} 取第一条,
     * 两条规则的表达式相同, 断言结果不受影响。
     */
    protected long seedActiveOrderRule(String expression) {
        long ruleId = nextId();
        // INTERVAL 的天数不能用占位符 (MySQL 不支持), 只能把整数拼进 SQL 文本。
        int daysBack = (int) RULE_SEQ.incrementAndGet();
        jdbc.update(
            "INSERT INTO t_billing_rule "
                + "(id, tenant_id, business_type, direction, counterparty_type, currency, priority, "
                + " status, effective_from, effective_to, remark, created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, 'ORDER', 'RECEIVABLE', 'CUSTOMER', 'CNY', 1, 'ACTIVE', "
                + " DATE_SUB(CURRENT_DATE, INTERVAL " + daysBack + " DAY), NULL, '4.6 IT fixture', "
                + " NOW(3), NOW(3), 0, 0)",
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

    // ==================================================================
    //  HTTP helpers
    // ==================================================================

    protected String loginAdmin() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"tenantId\":" + TEST_TENANT
                + ",\"username\":\"" + adminUsername + "\",\"password\":\"" + ADMIN_PWD + "\"}";
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/auth/login", HttpMethod.POST, new HttpEntity<>(body, h), R.class);
        assertThat(resp.getStatusCode().value()).as("admin login 应 200").isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).as("admin login 的 body.code 应为 0 (R.ok)").isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        String token = (String) data.get("accessToken");
        assertThat(token).as("admin login 应返回 accessToken").isNotBlank();
        return token;
    }

    protected HttpHeaders adminHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected ResponseEntity<R> adminGet(String path, HttpHeaders h) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), R.class);
    }

    protected ResponseEntity<R> adminPost(String path, String body, HttpHeaders h) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), R.class);
    }

    protected ResponseEntity<R> adminPut(String path, String body, HttpHeaders h) {
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, h), R.class);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> dataOf(ResponseEntity<R> resp) {
        assertThat(resp.getBody()).as("响应体不应为空").isNotNull();
        return (Map<String, Object>) resp.getBody().getData();
    }

    // ==================================================================
    //  request body helpers
    // ==================================================================

    /**
     * 构造 {@code POST /api/v1/orders} 的 body.
     *
     * <p>重量 100kg / 件数 10 件 / 服务项金额 1000 —— 供计费 IT 验算
     * (计费规则的表达式 {@code weight * 2} → gross 200)。
     *
     * <p><b>载荷必须满足 DTO 的 jakarta 约束</b> —— 否则 IT 会 400 而不是走通流程,
     * 会被误判成「状态机没生效」。逐条对照 {@code CreateOrderRequest} /
     * {@code CargoRequest} / {@code ServiceItemRequest} 上的注解:
     * {@code cargo[].cargoNameCn} "钢材" (@Size max=128) /
     * {@code cargo[].hsCode} "7208" (max=16) / {@code cargo[].pkgQty} 10 (@PositiveOrZero) /
     * {@code cargo[].weightKg} 100 / {@code cargo[].volumeCbm} 12.5 (@PositiveOrZero) /
     * {@code cargo[].currencyCode} "CNY" (max=3) /
     * {@code serviceItems[].feeType} "OCEAN_FREIGHT" (@NotBlank max=32) /
     * {@code serviceItems[].feeName} "海运费" (@NotBlank max=128) /
     * {@code serviceItems[].calcBasis} "PER_CONTAINER" (@NotBlank max=16) /
     * {@code serviceItems[].qty|unitPrice|amount} 1/1000/1000 (@PositiveOrZero) /
     * 头部 {@code businessType=OCEAN_FCL} {@code tradeDirection=EXPORT}
     * {@code incoterms=FOB} {@code serviceType=D2D} (四个都在 {@code OrderConstants} 的受控词表里) /
     * {@code settlementCurrency} "CNY" (@Size max=3) —— 全部合规。
     *
     * <p>body 含中文; 本仓既有的门户 IT ({@code PortalOrderCreateIT}) 用同样的写法回传中文
     * 并断言库里的中文原文, 编码链路已在本仓验证过。
     */
    protected String createOrderBody() {
        return createOrderBody("CNY");
    }

    /**
     * 同 {@link #createOrderBody()}, 但结算币种可换 ——
     * 「超长字段 → 400」那条 IT 用它传 4 个字符的 "USDD"。
     */
    protected String createOrderBody(String settlementCurrency) {
        return "{"
                + "\"customerId\":" + customerId + ","
                + "\"businessType\":\"OCEAN_FCL\","
                + "\"tradeDirection\":\"EXPORT\","
                + "\"incoterms\":\"FOB\","
                + "\"serviceType\":\"D2D\","
                + "\"originAddress\":\"上海洋山港\","
                + "\"destAddress\":\"洛杉矶港\","
                + "\"cargoReadyDate\":\"2026-11-20\","
                + "\"etd\":\"2026-11-25\","
                + "\"eta\":\"2026-12-10\","
                + "\"containerQty\":1,"
                + "\"containerTypeCode\":\"40HQ\","
                + "\"marksAndNos\":\"MSKU1234567\","
                + "\"cargoDescription\":\"五金配件\","
                + "\"isOversized\":false,"
                + "\"settlementCurrency\":\"" + settlementCurrency + "\","
                + "\"remark\":\"4.6 IT fixture\","
                + "\"cargo\":[{"
                + "\"cargoNameCn\":\"钢材\",\"cargoNameEn\":\"STEEL\",\"hsCode\":\"7208\","
                + "\"pkgType\":\"CARTON\",\"pkgQty\":10,\"unit\":\"CTN\","
                + "\"weightKg\":100,\"netWeightKg\":90,\"volumeCbm\":12.5,"
                + "\"lengthCm\":100,\"widthCm\":80,\"heightCm\":50,\"isStackable\":true,"
                + "\"cargoValue\":5000,\"currencyCode\":\"CNY\",\"isDangerous\":false"
                + "}],"
                + "\"serviceItems\":[{"
                + "\"feeType\":\"OCEAN_FREIGHT\",\"feeName\":\"海运费\",\"feeCategory\":\"FREIGHT\","
                + "\"calcBasis\":\"PER_CONTAINER\",\"qty\":1,\"unitPrice\":1000,\"amount\":1000,"
                + "\"currencyCode\":\"CNY\",\"isMandatory\":true"
                + "}]"
                + "}";
    }

    /** 构造带 reason 的转换端点 body。 */
    protected String reasonBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    /** 从 create 响应里取新建订单的 id。 */
    protected Long orderIdOf(ResponseEntity<R> resp) {
        assertThat(resp.getStatusCode().value()).as("建单应 200, 实际 %d", resp.getStatusCode().value())
                .isEqualTo(200);
        Map<String, Object> data = dataOf(resp);
        Object id = data.get("id");
        assertThat(id).as("建单响应应含 id").isNotNull();
        long orderId = ((Number) id).longValue();
        trackOrder(orderId);
        return orderId;
    }

    /** 库里当前的 order_status。 */
    protected String statusOf(long orderId) {
        return jdbc.queryForObject(
                "SELECT order_status FROM t_order WHERE id = ?", String.class, orderId);
    }

    /** 该订单发出的 outbox 事件数 (按 aggregate_type='ORDER' + aggregate_id 隔离)。 */
    protected int outboxCount(long orderId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_event_outbox WHERE aggregate_type = 'ORDER' AND aggregate_id = ?",
                Integer.class, String.valueOf(orderId));
        return n == null ? 0 : n;
    }
}
