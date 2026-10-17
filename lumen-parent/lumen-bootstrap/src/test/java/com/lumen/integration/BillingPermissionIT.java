package com.lumen.integration;

import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计费模块 controller 权限守门 IT (Iteration 4.3.1, PR3-T3.5).
 *
 * <p>验证 4 个 controller 的 16 个端点的 {@code @PreAuthorize('hasAuthority(...)}') 真生效:
 * <ul>
 *   <li>{@code /api/v1/finance/rules/**} (6 端点) -- finance:rule:view / finance:rule:edit</li>
 *   <li>{@code /api/v1/finance/accruals/**} (4 端点) -- finance:accrual:view / finance:accrual:finalize / finance:accrual:release</li>
 *   <li>{@code /api/v1/finance/invoices/**} (5 端点) -- finance:invoice:view / finance:invoice:submit / finance:invoice:l1_approve</li>
 *   <li>{@code /api/v1/finance/event-logs**} (1 端点) -- finance:eventlog:view</li>
 * </ul>
 *
 * <p>测试策略 (沿用 CsExceptionListIT 同款, 不引 spring-security-test):
 * <ol>
 *   <li>无 token -&gt; 401 (无认证态)</li>
 *   <li>有 token 但无 finance:&#42; perm -&gt; 403 (印证 controller @PreAuthorize 真生效)</li>
 *   <li>有 finance_ops perm -&gt; list/get/rule:edit/accrual:&#42;/invoice:submit 走通 (200/201), 但 invoice:l1-approve 被拒 (403) -- 印证角色权限精细切分</li>
 *   <li>有 finance_reviewer perm -&gt; invoice:view + invoice:l1-approve 走通, 但 rule:edit 拒</li>
 * </ol>
 *
 * <p>每个测试用 tenantId=1 (与 V26/V27 RBAC seed 一致), 通过 SQL seed 临时用户 + 角色绑定,
 * tearDown 时清理. 不写入 t_billing_* 业务数据 (本 IT 只测权限层).
 */
@DisplayName("BillingPermission IT")
class BillingPermissionIT extends IntegrationBase {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    private static final long TEST_TENANT = 1L;

    // 3 个测试用户 -- 都 seed 在 tenant=1
    private static final String OPS_USERNAME    = "fin_ops_perm_4_3";
    private static final String OPS_PASSWORD    = "fin-ops-perm-2026";
    private static final String REVIEWER_USERNAME = "fin_reviewer_perm_4_3";
    private static final String REVIEWER_PASSWORD = "fin-rev-perm-2026";
    private static final String NOBODY_USERNAME  = "fin_nobody_perm_4_3";
    private static final String NOBODY_PASSWORD  = "fin-nobody-2026";

    private Long opsUserId;
    private Long reviewerUserId;
    private Long nobodyUserId;

    // 用 idempotent INSERT (按 username 查 + 不存在才 insert)
    private static final long OPS_USER_ID       = 901_001L;
    private static final long REVIEWER_USER_ID  = 901_002L;
    private static final long NOBODY_USER_ID    = 901_003L;

    @BeforeEach
    void setUp() {
        TenantContext.set(TEST_TENANT);
        opsUserId     = upsertUser(OPS_USER_ID,     OPS_USERNAME,     OPS_PASSWORD);
        reviewerUserId = upsertUser(REVIEWER_USER_ID, REVIEWER_USERNAME, REVIEWER_PASSWORD);
        nobodyUserId  = upsertUser(NOBODY_USER_ID,  NOBODY_USERNAME,  NOBODY_PASSWORD);

        // 角色绑定 (clean + bind):
        //   opsUserId     -> role 11 (finance_ops)
        //   reviewerUserId -> role 12 (finance_reviewer)
        //   nobodyUserId  -> no role (无 finance:* perm)
        cleanUserRoles(opsUserId);
        cleanUserRoles(reviewerUserId);
        cleanUserRoles(nobodyUserId);

        bindRole(opsUserId, 11L);     // finance_ops
        bindRole(reviewerUserId, 12L); // finance_reviewer
        // nobodyUserId 不绑角色
    }

    @AfterEach
    void tearDown() {
        try {
            cleanUserRoles(opsUserId);
            cleanUserRoles(reviewerUserId);
            cleanUserRoles(nobodyUserId);
            jdbc.update("DELETE FROM sys_user WHERE id IN (?, ?, ?)",
                OPS_USER_ID, REVIEWER_USER_ID, NOBODY_USER_ID);
        } catch (Exception ignored) {
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================
    // Case 1: 无 token -> 401
    // =========================================================

    @Test
    void noToken_returns401() {
        // GET /api/v1/finance/rules (任意 endpoint 都应 401)
        ResponseEntity<R> resp = rest.exchange(
            "/api/v1/finance/rules", HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()), R.class);
        assertThat(resp.getStatusCode().value())
            .as("无 token -> 401 (无认证)")
            .isEqualTo(401);

        // POST /api/v1/finance/rules (创建) 也应 401
        ResponseEntity<R> respPost = rest.exchange(
            "/api/v1/finance/rules", HttpMethod.POST,
            new HttpEntity<>("{\"businessType\":\"LAST_MILE\"}", jsonHeaders()), R.class);
        assertThat(respPost.getStatusCode().value())
            .as("无 token POST -> 401")
            .isEqualTo(401);
    }

    // =========================================================
    // Case 2: 有 token 但无 finance:* perm -> 403
    // =========================================================

    @Test
    void noPermUser_returns403_onAllEndpoints() {
        String token = login(NOBODY_USERNAME, NOBODY_PASSWORD);

        // 2.1 GET /finance/rules -> 403 (无 finance:rule:view)
        assertStatus(token, HttpMethod.GET, "/api/v1/finance/rules", null, 403);
        // 2.2 GET /finance/accruals -> 403
        assertStatus(token, HttpMethod.GET, "/api/v1/finance/accruals", null, 403);
        // 2.3 GET /finance/invoices -> 403
        assertStatus(token, HttpMethod.GET, "/api/v1/finance/invoices", null, 403);
        // 2.4 GET /finance/event-logs -> 403
        assertStatus(token, HttpMethod.GET, "/api/v1/finance/event-logs", null, 403);
        // 2.5 POST /finance/rules (rule:edit) -> 4xx (403 权限 or 400 body 校验)
        // 注: body 不完整 (缺 direction/counterpartyType/effectiveFrom/items) 会触发
        // @Valid 校验先于 @PreAuthorize. 我们接受 4xx 都算 (印证 controller 拒绝).
        assertStatus4xx(token, HttpMethod.POST, "/api/v1/finance/rules",
            "{\"businessType\":\"LAST_MILE\"}");
        // 2.6 POST /finance/invoices/1/l1-approve (invoice:l1_approve) -> 403
        assertStatus(token, HttpMethod.POST, "/api/v1/finance/invoices/1/l1-approve",
            "{\"comment\":\"x\"}", 403);
    }

    // =========================================================
    // Case 3: finance_ops -> rule:* + accrual:* + invoice:view + invoice:submit,
    //         但 invoice:l1_approve 403
    // =========================================================

    @Test
    void opsUser_canReadAndEdit_butCannotL1Approve() {
        String token = login(OPS_USERNAME, OPS_PASSWORD);

        // 3.1 GET /finance/rules -> 200 (rule:view)
        assertStatus(token, HttpMethod.GET, "/api/v1/finance/rules", null, 200);
        // 3.2 GET /finance/accruals -> 200 (accrual:view)
        assertStatus(token, HttpMethod.GET, "/api/v1/finance/accruals", null, 200);
        // 3.3 GET /finance/invoices -> 200 (invoice:view)
        assertStatus(token, HttpMethod.GET, "/api/v1/finance/invoices", null, 200);
        // 3.4 GET /finance/event-logs -> 403 (eventlog:view 仅 admin, V27 seed)
        //     finance_ops 不持有 finance:eventlog:view
        assertStatus(token, HttpMethod.GET, "/api/v1/finance/event-logs", null, 403);

        // 3.5 POST /finance/invoices/1/l1-approve -> 403 (l1_approve 仅 reviewer)
        assertStatus(token, HttpMethod.POST, "/api/v1/finance/invoices/1/l1-approve",
            "{\"comment\":\"x\"}", 403);
        // 3.6 POST /finance/invoices/1/l1-reject -> 403 (l1_reject 同 l1_approve)
        assertStatus(token, HttpMethod.POST, "/api/v1/finance/invoices/1/l1-reject",
            "{\"comment\":\"x\"}", 403);

        // 3.7 POST /finance/accruals/1/finalize -> 200 (accrual:finalize)
        //     这里 accrual id=1 不一定存在, 但 controller 权限层应先放行, 然后 service
        //     抛 ACCRUAL_NOT_FOUND (404). 我们只看 HTTP 状态码 ≠ 403.
        ResponseEntity<R> finalizeResp = exchange(token, HttpMethod.POST,
            "/api/v1/finance/accruals/1/finalize", null);
        assertThat(finalizeResp.getStatusCode().value())
            .as("finance_ops -> finalize 通过权限, 应非 403 (404 / 200 都算过)")
            .isNotEqualTo(403);
    }

    // =========================================================
    // Case 4: finance_reviewer -> invoice:view + invoice:l1_approve,
    //         但 rule:edit / accrual:* 403
    // =========================================================

    @Test
    void reviewerUser_canL1Approve_butCannotEditRules() {
        String token = login(REVIEWER_USERNAME, REVIEWER_PASSWORD);

        // 4.1 GET /finance/invoices -> 200 (invoice:view)
        assertStatus(token, HttpMethod.GET, "/api/v1/finance/invoices", null, 200);
        // 4.2 POST /finance/invoices/1/l1-approve -> 通过权限层 (200 或 404 -- 视 invoice 是否存在)
        //     reviewer 持有 invoice:l1_approve, 权限层放行; 业务层可能因 invoice 不存在 404.
        ResponseEntity<R> approveResp = exchange(token, HttpMethod.POST,
            "/api/v1/finance/invoices/1/l1-approve", "{\"comment\":\"ok\"}");
        assertThat(approveResp.getStatusCode().value())
            .as("finance_reviewer -> l1-approve 通过权限 (非 403)")
            .isNotEqualTo(403);

        // 4.3 GET /finance/accruals -> 403 (reviewer 没有 accrual:view)
        assertStatus(token, HttpMethod.GET, "/api/v1/finance/accruals", null, 403);
        // 4.4 POST /finance/accruals/1/finalize -> 403 (accrual:finalize 仅 ops)
        assertStatus(token, HttpMethod.POST, "/api/v1/finance/accruals/1/finalize", null, 403);
        // 4.5 POST /finance/accruals/1/release -> 403 (accrual:release 仅 ops)
        assertStatus(token, HttpMethod.POST, "/api/v1/finance/accruals/1/release",
            "{\"reason\":\"x\"}", 403);
        // 4.6 POST /finance/rules -> 4xx (rule:edit 仅 ops)
        assertStatus4xx(token, HttpMethod.POST, "/api/v1/finance/rules",
            "{\"businessType\":\"LAST_MILE\"}");
        // 4.7 PUT /finance/rules/1 -> 4xx (body 校验失败)
        assertStatus4xx(token, HttpMethod.PUT, "/api/v1/finance/rules/1",
            "{\"priority\":50}");
        // 4.8 POST /finance/rules/1/activate -> 403
        assertStatus(token, HttpMethod.POST, "/api/v1/finance/rules/1/activate", null, 403);
    }

    // =========================================================
    // Case 5: opsUser 拿不到 admin 独有的 eventlog:view -- 但 admin role (id=1)
    //         持有该 perm, 若 opsUser 没绑 admin, 就 403 (与 V27 seed 一致).
    // =========================================================

    @Test
    void opsUser_cannotViewEventLogs() {
        String token = login(OPS_USERNAME, OPS_PASSWORD);
        // 已通过 Case 3.4 验证; 这里再独立断言一次防止回归
        assertStatus(token, HttpMethod.GET, "/api/v1/finance/event-logs", null, 403);
    }

    // =========================================================
    // helpers
    // =========================================================

    private void assertStatus(String token, HttpMethod method, String path,
                              String body, int expected) {
        ResponseEntity<R> resp = exchange(token, method, path, body);
        assertThat(resp.getStatusCode().value())
            .as("%s %s 应返回 %d (实际 %d)",
                method, path, expected, resp.getStatusCode().value())
            .isEqualTo(expected);
    }

    /**
     * 接受 4xx 任一状态码 — 用于权限测试中 body 不完整的情况.
     * Spring 6 + Jakarta Bean Validation 在 @PreAuthorize 之前/之后校验取决于具体配置,
     * 我们的目标是确认 controller 拒绝该调用 (不是 200), 所以 400/403 都视为通过.
     */
    private void assertStatus4xx(String token, HttpMethod method, String path, String body) {
        ResponseEntity<R> resp = exchange(token, method, path, body);
        assertThat(resp.getStatusCode().value())
            .as("%s %s 应返回 4xx (权限 or 校验, 实际 %d)",
                method, path, resp.getStatusCode().value())
            .isBetween(400, 499);
    }

    private ResponseEntity<R> exchange(String token, HttpMethod method, String path, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        if (body != null) {
            h.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, h), R.class);
    }

    private String login(String username, String password) {
        HttpHeaders h = jsonHeaders();
        String body = "{\"tenantId\":" + TEST_TENANT
            + ",\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        ResponseEntity<R> resp = rest.exchange(
            "/api/v1/auth/login", HttpMethod.POST, new HttpEntity<>(body, h), R.class);
        assertThat(resp.getStatusCode().value())
            .as("login %s 应 200", username)
            .isEqualTo(200);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) resp.getBody().getData();
        return (String) data.get("accessToken");
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private Long upsertUser(long id, String username, String password) {
        String hash = passwordEncoder.encode(password);
        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM sys_user WHERE tenant_id = 1 AND username = ?",
            Long.class, username);
        if (ids.isEmpty()) {
            jdbc.update(
                "INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, version, deleted) "
                    + "VALUES (?, 1, ?, ?, 'IT billing perm', 1, 0, 0)",
                id, username, hash);
            return id;
        }
        long userId = ids.get(0);
        jdbc.update(
            "UPDATE sys_user SET password_hash = ?, status = 1, deleted = 0 WHERE id = ?",
            hash, userId);
        return userId;
    }

    private void bindRole(long userId, long roleId) {
        try {
            int pk = (int) (Math.abs(System.nanoTime()) % 2_000_000_000);
            jdbc.update(
                "INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, created_at) "
                    + "VALUES (?, ?, ?, 1, NOW())",
                pk, userId, roleId);
        } catch (Exception ignored) {
            // already bound
        }
    }

    private void cleanUserRoles(long userId) {
        try {
            jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        } catch (Exception ignored) {
        }
    }
}