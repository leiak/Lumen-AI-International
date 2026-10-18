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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5A.3-T3.4 — 客户门户 (B2B) 运单追踪端点集成测试 (HTTP-level IT).
 *
 * <p>覆盖 {@code /api/customer/track} (list) / {@code /api/customer/track/{doId}} (detail) /
 * {@code /api/customer/track/{doId}/pod} (pod) 三个端点的客户隔离测试.
 *
 * <p>技术栈: 继承 {@link IntegrationBase} — 真实 MySQL (testcontainers) + Redis, 真实
 * Spring 上下文, 真实 HTTP via TestRestTemplate. {@code rest} 字段已绑定
 * {@code http://localhost:{随机端口}}; NoOpResponseErrorHandler 让 4xx 响应不抛异常.
 *
 * <p>Fixture 策略: 每个测试方法 {@code @BeforeEach} 重新 seed 用户 / 客户 / link;
 * 测试方法内根据需要 seed DOs (wms_delivery_order) + PODs (wms_delivery_pod).
 * 使用 {@link AtomicLong} 从 950_001L 递增生成 id, 避开既有 IT 占用段
 * (CsCrossModuleIT 900_001, RbacIsolationIT 9001, DispatchMasterDataIT 13800009001,
 * PortalAuthIT 950_001..950_006)。
 *
 * <p>关键流程:
 * <ol>
 *   <li>seed alice (customer 42) + carol (customer 99) — 两个 portal user;</li>
 *   <li>按测试需要 seed DOs (per-test);</li>
 *   <li>调 {@code POST /api/auth/portal/login} 拿 portal token (含 {@code active_customer_id} claim);</li>
 *   <li>用 {@code X-Tenant-Id: 1} + {@code Authorization: Bearer <token>}
 *       调 {@code /api/customer/track*} 端点 (T3.3 已记录的 workaround).</li>
 * </ol>
 *
 * <p>Spec deviations (vs. 计划文档):
 * <ul>
 *   <li><b>路径</b>: plan 写 {@code lumen-dispatch/.../it/PortalTrackIT.java}, 实际按既有
 *       IT 惯例放 {@code lumen-bootstrap/.../integration/PortalTrackIT.java} — 镜像
 *       {@link PortalAuthIT}, 复用 {@code IntegrationBase} 的 testcontainer.</li>
 *   <li><b>login workaround</b>: 用 {@code X-Tenant-Id: 1} header — T3.3 沿用.
 *       TenantInterceptor 读这个 header 设 TenantContext=1, 否则 MyBatis Plus 多租户
 *       拦截器会注入 {@code AND tenant_id = 0} 把 user 查询过滤成 0 行, portal 登录
 *       全部 401.</li>
 *   <li><b>case 2 解释</b>: plan 写 {@code Setup: customer 42 有 3 DO, customer 99 有 5 DO}
 *       + {@code // customer 99 调, 期望 0 条} 两者矛盾. 本 IT 按 test name 解释 — 用
 *       "客户 99 没有 DO" 场景做隔离验证, 故 {@code @BeforeEach} 不为 customer 99 seed DO.
 *       客户 99 的 DO 只在 case 3/4 (跨客户 404 测试) 临时 seed 1 条.</li>
 *   <li><b>case 5 timeline</b>: 当前 impl (4.5A.3 阶段) timeline 留空列表, 见
 *       {@code DeliveryOrderServiceImpl.getDetailForCustomer} 注释 "timeline 留待 4.5A.4+".
 *       本 IT 断言 timeline 字段存在且为 List (空列表), 不假装返回事件.</li>
 * </ul>
 */
@DisplayName("PortalTrack IT (4.5A.3 T3.4)")
class PortalTrackIT extends IntegrationBase {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    /**
     * Counter-based fixture id 生成器, 起始 950_001L 避开既有 IT 占用段 + PortalAuthIT
     * 占用 950_001..950_006. 业务数据由 AUTO_INCREMENT 自增, 不会撞 950_xxx.
     */
    private static final AtomicLong FIXTURE_ID = new AtomicLong(950_007L);

    // ===== fixture instance state =====
    private long aliceId;
    private long carolId;
    private long customer42Id;
    private long customer99Id;
    /**
     * DO.warehouse_id: V19 schema NOT NULL, 但无 FK 约束到 wms_warehouse.id, 因此可填
     * 任意 BIGINT. impl / mapper 不会去校验 warehouse 是否存在.
     */
    private long warehouseId;

    private final Set<Long> createdUserIds = new HashSet<>();
    private final Set<Long> createdCustomerIds = new HashSet<>();
    private final Set<Long> createdLinkIds = new HashSet<>();
    private final Set<Long> createdOrderIds = new HashSet<>();
    private final Set<Long> createdPodIds = new HashSet<>();

    @BeforeEach
    void setUp() {
        // alice (单客户 → customer 42)
        aliceId = nextId();
        seedUser(aliceId, "alice_track_454", "alice-track-pwd", "Alice Track");
        bindRole(aliceId, 14L);  // customer role id = 14 (V33)
        customer42Id = nextId();
        seedCustomer(customer42Id, "C042-454", "Acme Track 客户");
        bindLink(aliceId, customer42Id, 1);

        // carol (单客户 → customer 99)
        carolId = nextId();
        seedUser(carolId, "carol_track_454", "carol-track-pwd", "Carol Track");
        bindRole(carolId, 14L);
        customer99Id = nextId();
        seedCustomer(customer99Id, "C099-454", "Beta Track 客户");
        bindLink(carolId, customer99Id, 1);

        // 共享 DO warehouse_id (任意 BIGINT, 无 FK)
        warehouseId = nextId();
    }

    @AfterEach
    void tearDown() {
        try {
            // 先清 POD (依赖 DO)
            for (Long pid : createdPodIds) {
                try { jdbc.update("DELETE FROM wms_delivery_pod WHERE id = ?", pid); }
                catch (Exception ignored) {}
            }
            // 清 DO
            for (Long oid : createdOrderIds) {
                try { jdbc.update("DELETE FROM wms_delivery_order WHERE id = ?", oid); }
                catch (Exception ignored) {}
            }
            // 清 link (FK-like unique key 引用 user_id, customer_id)
            for (Long lid : createdLinkIds) {
                try { jdbc.update("DELETE FROM t_customer_user_link WHERE id = ?", lid); }
                catch (Exception ignored) {}
            }
            // 清 user_role binding
            for (Long uid : createdUserIds) {
                try { jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", uid); }
                catch (Exception ignored) {}
            }
            // 清 user
            for (Long uid : createdUserIds) {
                try { jdbc.update("DELETE FROM sys_user WHERE id = ?", uid); }
                catch (Exception ignored) {}
            }
            // 清 customer
            for (Long cid : createdCustomerIds) {
                try { jdbc.update("DELETE FROM wms_customer WHERE id = ?", cid); }
                catch (Exception ignored) {}
            }
        } finally {
            createdUserIds.clear();
            createdCustomerIds.clear();
            createdLinkIds.clear();
            createdOrderIds.clear();
            createdPodIds.clear();
        }
    }

    // ====================================================================
    // Case 1: alice (customer 42) 调 list, 期望看到自己 3 条 DO (records=3, total=3)
    // ====================================================================

    @Test
    @DisplayName("list_returnsOnlyMyCustomersDOs")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void list_returnsOnlyMyCustomersDOs() {
        // seed 3 DOs for customer 42
        for (int i = 1; i <= 3; i++) {
            seedDeliveryOrder(customer42Id, "DO-042-001-" + i, "PENDING_DISPATCH");
        }
        // customer 99 无 DO (case 2 才需要 carol 视角, 此 case 只验证 alice 看自己)

        String token = loginAsPortal("alice_track_454", "alice-track-pwd");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Tenant-Id", "1");

        ResponseEntity<R> resp = rest.exchange(
            "/api/customer/track?page=1&size=20",
            HttpMethod.GET, new HttpEntity<>(h), R.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        Number total = (Number) data.get("total");
        assertThat(records).as("alice 应看到 3 条 DO").hasSize(3);
        assertThat(total.longValue()).as("total=3").isEqualTo(3L);

        // 每条 record 应含 doId/doNo/status 字段
        for (Map<String, Object> row : records) {
            assertThat(row.get("doId")).isNotNull();
            assertThat(row.get("doNo")).isNotNull();
            assertThat(row.get("status")).isNotNull();
        }
    }

    // ====================================================================
    // Case 2: carol (customer 99) 调 list, 期望 0 条 (customer 42 的 3 条 DO 不能泄漏)
    // ====================================================================

    @Test
    @DisplayName("list_customerBSeesEmptyWhenCustomerAHasDOs")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void list_customerBSeesEmptyWhenCustomerAHasDOs() {
        // seed 3 DOs for customer 42 (alice 的客户)
        for (int i = 1; i <= 3; i++) {
            seedDeliveryOrder(customer42Id, "DO-042-002-" + i, "PENDING_DISPATCH");
        }
        // customer 99 没有 DO, carol 应看不到 alice 客户的 DO

        String token = loginAsPortal("carol_track_454", "carol-track-pwd");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Tenant-Id", "1");

        ResponseEntity<R> resp = rest.exchange(
            "/api/customer/track?page=1&size=20",
            HttpMethod.GET, new HttpEntity<>(h), R.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        Number total = (Number) data.get("total");
        assertThat(records).as("carol 应看不到 alice 客户的 DO").isEmpty();
        assertThat(total.longValue()).as("total=0 (隔离成立)").isEqualTo(0L);
    }

    // ====================================================================
    // Case 3: alice (customer 42) 调 GET /track/{customer 99 的 DO id} → 404
    // DeliveryOrderService.getDetailForCustomer 抛 DeliveryOrderNotFoundException
    // → BizException → handleBiz → HTTP 404 + body.code=60004
    // ====================================================================

    @Test
    @DisplayName("getDetail_otherCustomersDO_returns404")
    void getDetail_otherCustomersDO_returns404() {
        // alice 有自己的 DO (sanity, 避免 alice 的 customer 42 一条 DO 都没有时实现可能
        // 走"customerId 不匹配"分支而非 "DO 不存在" 分支)
        long aliceOrderId = seedDeliveryOrder(customer42Id, "DO-042-003-A", "PENDING_DISPATCH");
        // carol 客户的 DO (alice 想跨客户访问)
        long carolOrderId = seedDeliveryOrder(customer99Id, "DO-099-003-B", "PENDING_DISPATCH");

        String token = loginAsPortal("alice_track_454", "alice-track-pwd");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Tenant-Id", "1");

        ResponseEntity<R> resp = rest.exchange(
            "/api/customer/track/" + carolOrderId,
            HttpMethod.GET, new HttpEntity<>(h), R.class);

        assertThat(resp.getStatusCode().value())
            .as("跨客户 DO 访问应抛 DeliveryOrderNotFoundException → handleBiz → 404 "
                + "(不暴露存在性, 防止 customer 绕过)")
            .isEqualTo(404);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode())
            .as("DispatchErrorCode.DELIVERY_ORDER_NOT_FOUND.code=60004")
            .isEqualTo(60004);
        assertThat(resp.getBody().getMessage())
            .as("DeliveryOrderNotFoundException 构造器用 '配送单未找到: id=...' 覆盖默认 '配送单不存在'")
            .contains("配送单未找到");

        // sanity: alice 访问自己 DO 应 200
        ResponseEntity<R> ownResp = rest.exchange(
            "/api/customer/track/" + aliceOrderId,
            HttpMethod.GET, new HttpEntity<>(h), R.class);
        assertThat(ownResp.getStatusCode().value()).isEqualTo(200);
    }

    // ====================================================================
    // Case 4: alice (customer 42) 调 GET /track/{customer 99 DO id}/pod → 404
    // (T3.2 spec review C1 fix 验证 — 此前 pod 端点会泄漏其它客户的 POD 数据)
    // PortalTrackController.pod 先调 getDetailForCustomer 做 ownership guard
    // → DeliveryOrderNotFoundException → 404
    // ====================================================================

    @Test
    @DisplayName("getPod_otherCustomersDO_returns404")
    void getPod_otherCustomersDO_returns404() {
        // 给 customer 99 的 DO 一个 POD 行 (确保 carolOrderId 不为空 POD 的存在性,
        // 避免"找不到 POD"路径掩盖跨客户访问)
        long carolOrderId = seedSignedDeliveryOrderWithPod(
            customer99Id, "DO-099-004", "/pod/customer99.png", "Carol's Signer");

        String token = loginAsPortal("alice_track_454", "alice-track-pwd");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Tenant-Id", "1");

        ResponseEntity<R> resp = rest.exchange(
            "/api/customer/track/" + carolOrderId + "/pod",
            HttpMethod.GET, new HttpEntity<>(h), R.class);

        // PodServiceImpl 本身不做 ownership 校验 (impl 直接按 doId 查 POD 表);
        // 防护由 PortalTrackController.pod 调 getDetailForCustomer 提前 404.
        // 即: alice 永远不应该看到 carolOrderId 的 POD, 即使该 POD 在 POD 表里存在.
        assertThat(resp.getStatusCode().value())
            .as("/pod 端点必须复用 getDetailForCustomer 的 ownership guard — T3.2 fix 验证")
            .isEqualTo(404);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode())
            .as("DeliveryOrderNotFoundException → handleBiz → code=60004")
            .isEqualTo(60004);
    }

    // ====================================================================
    // Case 5: alice (customer 42) 调 GET /track/{自己 DO id}, 期望 200 +
    // 含 timeline 字段 + /pod 端点返回 POD 数据
    // ====================================================================

    @Test
    @DisplayName("getDetail_ownDO_returnsTimelineAndPOD")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void getDetail_ownDO_returnsTimelineAndPOD() {
        long aliceOrderId = seedSignedDeliveryOrderWithPod(
            customer42Id, "DO-042-005", "/pod/alice.png", "Alice Signer");

        String token = loginAsPortal("alice_track_454", "alice-track-pwd");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Tenant-Id", "1");

        // (a) /track/{doId} detail
        ResponseEntity<R> detailResp = rest.exchange(
            "/api/customer/track/" + aliceOrderId,
            HttpMethod.GET, new HttpEntity<>(h), R.class);
        assertThat(detailResp.getStatusCode().value()).isEqualTo(200);
        assertThat(detailResp.getBody()).isNotNull();
        assertThat(detailResp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> detail = (Map<String, Object>) detailResp.getBody().getData();
        assertThat(detail).as("own DO detail 数据应非空").isNotNull();
        assertThat(((Number) detail.get("doId")).longValue()).isEqualTo(aliceOrderId);
        assertThat(detail.get("doNo")).isEqualTo("DO-042-005");
        assertThat(detail.get("status")).isEqualTo("SIGNED");
        // timeline 当前 impl 是 List (4.5A.3 阶段为空列表, 见 impl 注释 "timeline 留待 4.5A.4+")
        Object timeline = detail.get("timeline");
        assertThat(timeline).as("timeline 字段必须存在 (即使为空)").isInstanceOf(List.class);
        assertThat((List<?>) timeline).as("4.5A.3 timeline 留空 — 不假装返回事件").isEmpty();

        // (b) /track/{doId}/pod
        ResponseEntity<R> podResp = rest.exchange(
            "/api/customer/track/" + aliceOrderId + "/pod",
            HttpMethod.GET, new HttpEntity<>(h), R.class);
        assertThat(podResp.getStatusCode().value()).isEqualTo(200);
        assertThat(podResp.getBody()).isNotNull();
        assertThat(podResp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> pod = (Map<String, Object>) podResp.getBody().getData();
        assertThat(pod).isNotNull();
        assertThat(((Number) pod.get("doId")).longValue()).isEqualTo(aliceOrderId);
        assertThat(pod.get("podType")).isEqualTo("PHOTO");
        assertThat(pod.get("imageUrl")).isEqualTo("/pod/alice.png");
        assertThat(pod.get("signerName")).isEqualTo("Alice Signer");
        assertThat(pod.get("signedAt")).as("signedAt 应存在").isNotNull();
    }

    // ====================================================================
    // helpers — fixture seeding
    // ====================================================================

    private long nextId() {
        return FIXTURE_ID.incrementAndGet();
    }

    private void seedUser(long id, String username, String rawPassword, String realName) {
        String hash = passwordEncoder.encode(rawPassword);
        jdbc.update(
            "INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, version, deleted, created_at, updated_at) "
                + "VALUES (?, 1, ?, ?, ?, 1, 0, 0, NOW(), NOW())",
            id, username, hash, realName);
        createdUserIds.add(id);
    }

    private void bindRole(long userId, long roleId) {
        // 用 nanoTime 派生 sys_user_role.id 避开 unique key 冲突 (重复跑同一 case 时)
        long userRolePk = Math.abs(System.nanoTime()) % 2_000_000_000L;
        try {
            jdbc.update(
                "INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, created_at) "
                    + "VALUES (?, ?, ?, 1, NOW())",
                userRolePk, userId, roleId);
        } catch (Exception dup) {
            // already bound — ignore (幂等)
        }
    }

    private void seedCustomer(long id, String code, String name) {
        // wms_customer schema (V15) 没有 status 列 — 仅 code/name/contact/phone/address
        // + tenant_id + 审计列 (created_at/updated_at/deleted/version).
        jdbc.update(
            "INSERT INTO wms_customer (id, tenant_id, code, name, deleted, version, created_at, updated_at) "
                + "VALUES (?, 1, ?, ?, 0, 1, NOW(), NOW())",
            id, code, name);
        createdCustomerIds.add(id);
    }

    private void bindLink(long userId, long customerId, int isPrimary) {
        long linkPk = Math.abs(System.nanoTime()) % 2_000_000_000L;
        jdbc.update(
            "INSERT INTO t_customer_user_link (id, tenant_id, user_id, customer_id, is_primary, deleted, version, created_at, updated_at) "
                + "VALUES (?, 1, ?, ?, ?, 0, 1, NOW(), NOW())",
            linkPk, userId, customerId, isPrimary);
        createdLinkIds.add(linkPk);
    }

    /**
     * Seed a {@code wms_delivery_order} 行 (V19 schema, V34 加 {@code customer_id} 列).
     *
     * <p>必填 NOT NULL 字段 (V19): {@code tenant_id, source_no, no, status, warehouse_id,
     * total_weight_kg, total_volume_m3, stop_count, sign_required, created_at, updated_at,
     * deleted, version}. {@code source_type} NOT NULL DEFAULT 'OUTBOUND' — 不显式指定时
     * MySQL 自动应用 DEFAULT.
     */
    private long seedDeliveryOrder(long customerId, String doNo, String status) {
        long id = nextId();
        jdbc.update(
            "INSERT INTO wms_delivery_order "
                + "(id, tenant_id, customer_id, source_no, source_type, no, status, warehouse_id, "
                + " total_weight_kg, total_volume_m3, stop_count, sign_required, "
                + " created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, ?, 'SRC-XXX', 'OUTBOUND', ?, ?, ?, "
                + " 0, 0, 0, 1, NOW(), NOW(), 0, 1)",
            id, customerId, doNo, status, warehouseId);
        createdOrderIds.add(id);
        return id;
    }

    /**
     * Seed a SIGNED {@code wms_delivery_order} + 配套 {@code wms_delivery_pod} 行 (V35 schema).
     * 给 4.5A.3 阶段的"customer 查自己已签收 DO + POD" 路径提供 fixture.
     */
    private long seedSignedDeliveryOrderWithPod(long customerId, String doNo,
                                                String imageUrl, String signerName) {
        long orderId = nextId();
        jdbc.update(
            "INSERT INTO wms_delivery_order "
                + "(id, tenant_id, customer_id, source_no, source_type, no, status, warehouse_id, "
                + " sign_required, sign_photo_url, delivered_at, "
                + " total_weight_kg, total_volume_m3, stop_count, "
                + " created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, ?, 'SRC-XXX', 'OUTBOUND', ?, 'SIGNED', ?, "
                + " 1, ?, NOW(), "
                + " 0, 0, 0, NOW(), NOW(), 0, 1)",
            orderId, customerId, doNo, warehouseId, imageUrl);
        createdOrderIds.add(orderId);

        long podId = nextId();
        jdbc.update(
            "INSERT INTO wms_delivery_pod "
                + "(id, tenant_id, do_id, pod_type, image_url, signer_name, signed_at, "
                + " created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, ?, 'PHOTO', ?, ?, NOW(), NOW(), NOW(), 0, 1)",
            podId, orderId, imageUrl, signerName);
        createdPodIds.add(podId);
        return orderId;
    }

    @SuppressWarnings("unchecked")
    private String loginAsPortal(String username, String password) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        // T3.3 workaround: portal login 走 X-Tenant-Id=1 才能让 TenantInterceptor 设
        // TenantContext=1, 否则 MyBatis Plus 多租户拦截器把 user 查询过滤成 0 行.
        h.set("X-Tenant-Id", "1");
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        ResponseEntity<R> resp = rest.exchange(
            "/api/auth/portal/login", HttpMethod.POST, new HttpEntity<>(body, h), R.class);

        assertThat(resp.getStatusCode().value())
            .as("portal login should succeed for seed user " + username)
            .isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        String token = (String) data.get("token");
        assertThat(token).as("portal login should return non-blank token").isNotBlank();
        return token;
    }
}