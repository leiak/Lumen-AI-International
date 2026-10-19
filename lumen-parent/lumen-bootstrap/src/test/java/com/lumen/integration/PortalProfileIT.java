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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5A.6 — 客户门户 profile / choices 端点集成测试 (HTTP-level IT).
 *
 * <p>覆盖 Bug 1/2/3 fix 的真实数据库路径:
 * <ul>
 *   <li>{@code GET /api/customer/profile} — 校验返回 code/name/contact/phone/address 来自
 *       {@code wms_customer} 表;</li>
 *   <li>{@code PUT /api/customer/profile} — 修改 phone/address 真存到 DB;</li>
 *   <li>{@code GET /api/customer/choices} — 返当前用户关联的全部客户 (单/多 customer);
 *       current active 标 isPrimary=true; 其它标 false.</li>
 * </ul>
 *
 * <p>Fixture 策略: 复用 {@link PortalAuthIT} 同款 fixtures — 用户/客户/role/link 行均通过
 * {@code @BeforeEach} seed, 用 {@link AtomicLong} 从 950_007L 起递增 (PortalAuthIT 占
 * 950_001..950_006, PortalTrackIT 占 950_008+), 测试方法结束后清理.
 */
@DisplayName("PortalProfile IT (4.5A.6 T fix)")
class PortalProfileIT extends IntegrationBase {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    /** PortalProfileIT 用 950_200+ 段避开 PortalAuthIT (950_001..950_006) + PortalTrackIT (950_008+). */
    private static final AtomicLong FIXTURE_ID = new AtomicLong(950_200L);

    // ===== fixture instance state =====
    private long aliceSingleId;
    private long aliceMultiId;
    private long customer1Id;
    private long customer2Id;
    private long customer3Id;

    private final Set<Long> createdUserIds = new HashSet<>();
    private final Set<Long> createdCustomerIds = new HashSet<>();
    private final Set<Long> createdLinkIds = new HashSet<>();

    @BeforeEach
    void setUp() {
        // alice 单客户 → C001 (主)
        aliceSingleId = nextId();
        seedUser(aliceSingleId, "alice_prof_45A6", "alice-prof-pwd", "Alice Profile");
        bindRole(aliceSingleId, 14L);
        customer1Id = nextId();
        seedCustomer(customer1Id, "C-PROF-A", "Profile 客户 A", "Alice Wong", "13800000001", "上海市黄浦区");
        bindLink(aliceSingleId, customer1Id, 1);

        // alice 多客户 → C002 (非主) + C003 (主)
        aliceMultiId = nextId();
        seedUser(aliceMultiId, "alice_multi_45A6", "alice-multi-prof-pwd", "Alice Multi Profile");
        bindRole(aliceMultiId, 14L);
        customer2Id = nextId();
        seedCustomer(customer2Id, "C-PROF-B", "Profile 客户 B", "Bob Liu", "13800000002", "北京市朝阳区");
        bindLink(aliceMultiId, customer2Id, 0);
        customer3Id = nextId();
        seedCustomer(customer3Id, "C-PROF-C", "Profile 客户 C", "Carol Chen", "13800000003", "深圳市南山区");
        bindLink(aliceMultiId, customer3Id, 1);
    }

    @AfterEach
    void tearDown() {
        try {
            for (Long linkId : createdLinkIds) {
                try { jdbc.update("DELETE FROM t_customer_user_link WHERE id = ?", linkId); }
                catch (Exception ignored) {}
            }
            for (Long uid : createdUserIds) {
                try { jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", uid); }
                catch (Exception ignored) {}
            }
            for (Long uid : createdUserIds) {
                try { jdbc.update("DELETE FROM sys_user WHERE id = ?", uid); }
                catch (Exception ignored) {}
            }
            for (Long cid : createdCustomerIds) {
                try { jdbc.update("DELETE FROM wms_customer WHERE id = ?", cid); }
                catch (Exception ignored) {}
            }
        } finally {
            createdUserIds.clear();
            createdCustomerIds.clear();
            createdLinkIds.clear();
        }
    }

    // ====================================================================
    // Case 1: GET /profile — Bug 1 fix 验证
    // 期望: 返回 code/name/contact/phone/address 全部来自 wms_customer, 不是 hardcoded note
    // ====================================================================

    @Test
    @DisplayName("getProfile_returnsAllWmsCustomerFields")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void getProfile_returnsAllWmsCustomerFields() {
        String token = loginAsPortal("alice_prof_45A6", "alice-prof-pwd");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Tenant-Id", "1");
        ResponseEntity<R> resp = rest.exchange(
            "/api/customer/profile", HttpMethod.GET, new HttpEntity<>(h), R.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        assertThat(data).as("profile 应返完整字段 (含 phone/address)").isNotNull();
        assertThat(((Number) data.get("customerId")).longValue()).isEqualTo(customer1Id);
        assertThat(data.get("code")).isEqualTo("C-PROF-A");
        assertThat(data.get("name")).isEqualTo("Profile 客户 A");
        assertThat(data.get("contact")).isEqualTo("Alice Wong");
        assertThat(data.get("phone")).isEqualTo("13800000001");
        assertThat(data.get("address")).isEqualTo("上海市黄浦区");
        // Bug 1 fix 验证: 老 stub 的 note 字段不应出现 (Map.of 没有 note key)
        assertThat(data).doesNotContainKey("note");
    }

    // ====================================================================
    // Case 2: PUT /profile — Bug 2 fix 验证
    // 期望: 提交 { phone, address } 后 DB 中对应字段真更新; GET /profile 读到新值
    // ====================================================================

    @Test
    @DisplayName("updateProfile_phoneAddress_persistsToWmsCustomer")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void updateProfile_phoneAddress_persistsToWmsCustomer() {
        String token = loginAsPortal("alice_prof_45A6", "alice-prof-pwd");

        // (a) 改 phone/address
        HttpHeaders putH = new HttpHeaders();
        putH.setContentType(MediaType.APPLICATION_JSON);
        putH.setBearerAuth(token);
        putH.set("X-Tenant-Id", "1");
        String body = "{\"phone\":\"13911112222\",\"address\":\"徐汇区漕河泾路 1 号\"}";
        ResponseEntity<R> putResp = rest.exchange(
            "/api/customer/profile", HttpMethod.PUT, new HttpEntity<>(body, putH), R.class);
        assertThat(putResp.getStatusCode().value())
            .as("PUT /profile 应返 200 (4.5A.6 修复: 真实 save)")
            .isEqualTo(200);

        // (b) 直接 SQL 查 DB 验证写入了
        String phoneInDb = jdbc.queryForObject(
            "SELECT phone FROM wms_customer WHERE id = ?", String.class, customer1Id);
        String addressInDb = jdbc.queryForObject(
            "SELECT address FROM wms_customer WHERE id = ?", String.class, customer1Id);
        assertThat(phoneInDb).isEqualTo("13911112222");
        assertThat(addressInDb).isEqualTo("徐汇区漕河泾路 1 号");

        // (c) 再 GET /profile 验证读出新值
        HttpHeaders getH = new HttpHeaders();
        getH.setBearerAuth(token);
        getH.set("X-Tenant-Id", "1");
        ResponseEntity<R> getResp = rest.exchange(
            "/api/customer/profile", HttpMethod.GET, new HttpEntity<>(getH), R.class);
        Map<String, Object> data = (Map<String, Object>) getResp.getBody().getData();
        assertThat(data.get("phone")).isEqualTo("13911112222");
        assertThat(data.get("address")).isEqualTo("徐汇区漕河泾路 1 号");
    }

    // ====================================================================
    // Case 3: GET /choices — Bug 3 fix 验证 (single customer)
    // 期望: 返 [C-PROF-A], isPrimary=true
    // ====================================================================

    @Test
    @DisplayName("choices_singleCustomer_returnsOneEntryMarkedActive")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void choices_singleCustomer_returnsOneEntryMarkedActive() {
        String token = loginAsPortal("alice_prof_45A6", "alice-prof-pwd");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Tenant-Id", "1");
        ResponseEntity<R> resp = rest.exchange(
            "/api/customer/choices", HttpMethod.GET, new HttpEntity<>(h), R.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // /choices 直接返 R<List<...>>: data 是 List<Map<String,Object>>, 不是 Map 包 List
        java.util.List<Map<String, Object>> entries =
            (java.util.List<Map<String, Object>>) resp.getBody().getData();
        assertThat(entries).hasSize(1);

        Map<String, Object> entry = entries.get(0);
        assertThat(((Number) entry.get("id")).longValue()).isEqualTo(customer1Id);
        assertThat(entry.get("code")).isEqualTo("C-PROF-A");
        assertThat(entry.get("name")).isEqualTo("Profile 客户 A");
        assertThat(entry.get("isPrimary")).isEqualTo(true);
    }

    // ====================================================================
    // Case 4 (4.5A.6-Bug4): GET /profile — customer 行不存在时返 404 (而非 500)
    //   修复前: CustomerException 落到 GlobalExceptionHandler.handleAny → HTTP 500.
    //   修复后: CustomerExceptionHandler 路由到 code=404_001 → HTTP 404 + body.code=404001.
    // ====================================================================

    @Test
    @DisplayName("getProfile_customerRowMissing_returns404_not500")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void getProfile_customerRowMissing_returns404_not500() {
        // 1) 先正常 login (setup 已 seed customer1Id + 绑定 alice_prof_45A6)
        String token = loginAsPortal("alice_prof_45A6", "alice-prof-pwd");

        // 2) 把 customer 行从 wms_customer 删掉, 模拟 "token 中 customerId 已绑但行被清" 的 fail-fast 路径
        int rowsDeleted = jdbc.update("DELETE FROM wms_customer WHERE id = ?", customer1Id);
        assertThat(rowsDeleted)
            .as("precondition: customer1Id 行应已存在并被本次 DELETE 移除")
            .isEqualTo(1);
        // 从 cleanup 集合移除 — 已被本测试清掉, 否则 @AfterEach 再删会 no-op 但仍安全
        createdCustomerIds.remove(customer1Id);

        // 3) GET /profile — Bug4 期望: HTTP 404 + body.code=404001 (而非 HTTP 500)
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Tenant-Id", "1");
        ResponseEntity<R> resp = rest.exchange(
            "/api/customer/profile", HttpMethod.GET, new HttpEntity<>(h), R.class);

        assertThat(resp.getStatusCode().value())
            .as("Bug4 fix: 404_001 路由后应返 HTTP 404, 不再是 GlobalExceptionHandler 的 500")
            .isEqualTo(404);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode())
            .as("body.code 应等于 404001 (404_001 in JSON)")
            .isEqualTo(404001);
        assertThat(resp.getBody().getMessage()).isEqualTo("客户不存在");
    }

    // ====================================================================
    // Case 5: GET /choices — Bug 3 fix 验证 (multi customer)
    // 期望: 返 2 个 customer (C-PROF-B 非主 + C-PROF-C 主), active=C-PROF-C 标 isPrimary=true
    // ====================================================================

    @Test
    @DisplayName("choices_multiCustomer_returnsAll_markActiveAsPrimary")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void choices_multiCustomer_returnsAll_markActiveAsPrimary() {
        // first: multi customer login → choices (没 token, multi-customer)
        HttpHeaders loginH = new HttpHeaders();
        loginH.setContentType(MediaType.APPLICATION_JSON);
        loginH.set("X-Tenant-Id", "1");
        String loginBody = "{\"username\":\"alice_multi_45A6\",\"password\":\"alice-multi-prof-pwd\"}";
        ResponseEntity<R> loginResp = rest.exchange(
            "/api/auth/portal/login", HttpMethod.POST, new HttpEntity<>(loginBody, loginH), R.class);
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> loginData = (Map<String, Object>) loginResp.getBody().getData();
        assertThat(loginData.get("token"))
            .as("multi-customer login 不应直接发 token").isNull();
        Number multiUserId = (Number) loginData.get("userId");

        // second: select-customer (用 C-PROF-C 作为 active primary)
        HttpHeaders selectH = new HttpHeaders();
        selectH.setContentType(MediaType.APPLICATION_JSON);
        selectH.set("X-Tenant-Id", "1");
        String selectBody = "{\"userId\":" + multiUserId.longValue() + ",\"customerId\":" + customer3Id + "}";
        ResponseEntity<R> selectResp = rest.exchange(
            "/api/auth/portal/select-customer", HttpMethod.POST, new HttpEntity<>(selectBody, selectH), R.class);
        assertThat(selectResp.getStatusCode().value()).isEqualTo(200);
        String token = (String) ((Map<String, Object>) selectResp.getBody().getData()).get("token");
        assertThat(token).isNotBlank();

        // third: GET /choices 应返 [C-PROF-B, C-PROF-C] (按 SQL 排序: C-PROF-C primary 在前)
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Tenant-Id", "1");
        ResponseEntity<R> choicesResp = rest.exchange(
            "/api/customer/choices", HttpMethod.GET, new HttpEntity<>(h), R.class);

        assertThat(choicesResp.getStatusCode().value()).isEqualTo(200);
        java.util.List<Map<String, Object>> entries =
            (java.util.List<Map<String, Object>>) choicesResp.getBody().getData();
        assertThat(entries).hasSize(2);

        // 第一个 (primary=true) 必须是 active customer (customer3Id)
        assertThat(((Number) entries.get(0).get("id")).longValue()).isEqualTo(customer3Id);
        assertThat(entries.get(0).get("code")).isEqualTo("C-PROF-C");
        assertThat(entries.get(0).get("isPrimary")).isEqualTo(true);

        // 第二个 (primary=false) 是另一个 customer
        assertThat(((Number) entries.get(1).get("id")).longValue()).isEqualTo(customer2Id);
        assertThat(entries.get(1).get("code")).isEqualTo("C-PROF-B");
        assertThat(entries.get(1).get("isPrimary")).isEqualTo(false);
    }

    // ====================================================================
    // helpers — fixture seeding (mirror PortalAuthIT style)
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
        long userRolePk = Math.abs(System.nanoTime()) % 2_000_000_000L;
        try {
            jdbc.update(
                "INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, created_at) "
                    + "VALUES (?, ?, ?, 1, NOW())",
                userRolePk, userId, roleId);
        } catch (Exception dup) {
            // already bound — ignore
        }
    }

    private void seedCustomer(long id, String code, String name, String contact, String phone, String address) {
        // 4.5A.6 fix: seed 时一并填 contact/phone/address 给 Case 1 验证可读
        jdbc.update(
            "INSERT INTO wms_customer (id, tenant_id, code, name, contact, phone, address, deleted, version, created_at, updated_at) "
                + "VALUES (?, 1, ?, ?, ?, ?, ?, 0, 1, NOW(), NOW())",
            id, code, name, contact, phone, address);
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

    private String loginAsPortal(String username, String password) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
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
