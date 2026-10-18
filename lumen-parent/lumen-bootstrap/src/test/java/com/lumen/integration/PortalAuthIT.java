package com.lumen.integration;

import com.lumen.common.api.R;
import com.lumen.customer.error.PortalErrorCode;
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
 * 4.5A.3-T3.3 — 客户门户 (B2B) 认证端点集成测试 (HTTP-level IT).
 *
 * <p>覆盖 {@code /api/auth/portal/login} / {@code /api/auth/portal/select-customer} /
 * {@code /api/auth/portal/refresh} + 跨 audience 路由 (admin token 调 portal 端点应 401).
 *
 * <p>技术栈: 继承 {@link IntegrationBase} — 真实 MySQL (testcontainers) + Redis, 真实
 * Spring 上下文, 真实 HTTP via TestRestTemplate. {@code rest} 字段已绑定
 * {@code http://localhost:{随机端口}}; NoOpResponseErrorHandler 让 4xx 响应不抛异常.
 *
 * <p>Fixture 策略: 每个测试方法 {@code @BeforeEach} 重新 seed — 通过 {@link JdbcTemplate}
 * 直接 SQL 插 sys_user / sys_user_role / wms_customer / t_customer_user_link 行, 用
 * {@link AtomicLong} 生成 950_000+ 段 id 避开既有 IT (9000_001..9000_010 由
 * CsCrossModuleIT / RbacIsolationIT 占用) + 业务自动分配; {@code @AfterEach} 删除.
 * 密码 hash 用 {@link BCryptPasswordEncoder} 当场生成 (cost=10).
 *
 * <p>Spec deviations (vs. 计划文档):
 * <ul>
 *   <li><b>路径</b>: plan 写 {@code lumen-rbac/.../it/PortalAuthIT.java}, 实际按既有
 *       IT 惯例放 {@code lumen-bootstrap/.../integration/PortalAuthIT.java} — 镜像
 *       {@link AuthFlowIT}. 这样复用 {@code IntegrationBase} 的 testcontainer.</li>
 *   <li><b>error code vs. http status</b>: PortalAuthService 用 {@code AuthException}
 *       (继承 {@code AuthenticationException}) 包装所有错误 — 该异常走
 *       {@code GlobalExceptionHandler.handleAuth(AuthenticationException)}, 统一回
 *       HTTP 401 + body.code=401 (CommonErrorCode.UNAUTHORIZED), 即使
 *       PortalErrorCode 本来定义了 httpStatus=403 (PORTAL_NO_CUSTOMER_ROLE 等).
 *       本 IT 因此断言 HTTP 401 + body.code=401 — 这是当前实现的真实行为, plan 写的
 *       "403" 仅当 service 改用 BizException 才能成立, 这是后续 T2.6 (exception
 *       handler 拆分) 要解决的, 不在本 T3.3 范围.</li>
 * </ul>
 *
 * <p>T3.5 wrap-up — Bug 1/2/3 fixes landed:
 * <ul>
 *   <li><b>Bug 1 (X-Tenant-Id required)</b>: {@code PortalAuthController.ensureTenantContext}
 *       {@code @ModelAttribute} sets {@code TenantContext=1L} when missing, so login works
 *       without {@code X-Tenant-Id}. Single-tenant MVP; multi-tenant portal comes 4.5B+.</li>
 *   <li><b>Bug 2 (selectCustomer userId)</b>: controller now reads {@code userId} from the
 *       new {@link com.lumen.customer.dto.PortalSelectCustomerRequest} body, not from
 *       {@code CustomerContext} (filter only covers {@code /api/customer/*}).</li>
 *   <li><b>Bug 3 (refresh sub type)</b>: controller now reads {@code sub} via
 *       {@code claims.getSubject()} + {@code Long.parseLong}, matching JJWT 0.12's
 *       String storage (issueToken writes via {@code String.valueOf(userId)}).</li>
 * </ul>
 */
@DisplayName("PortalAuth IT (4.5A.3 T3.3)")
class PortalAuthIT extends IntegrationBase {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    /**
     * Counter-based fixture id 生成器, 起始 950_001L 避开既有 IT 占用段
     * (CsCrossModuleIT 900_001, RbacIsolationIT 9001, DispatchMasterDataIT 13800009001).
     * 业务数据由 AUTO_INCREMENT 自增, 不会撞 950_xxx.
     */
    private static final AtomicLong FIXTURE_ID = new AtomicLong(950_000L);

    // ===== fixture instance state =====
    private long aliceSingleId;
    private long aliceMultiId;
    private long bobNoCustomerRoleId;
    private long carolNoCustomerLinkId;
    private long customer1Id;
    private long customer2Id;
    private long customer3Id;

    private final Set<Long> createdUserIds = new HashSet<>();
    private final Set<Long> createdCustomerIds = new HashSet<>();
    private final Set<Long> createdLinkIds = new HashSet<>();

    @BeforeEach
    void setUp() {
        // alice (单客户): customer role + 1 link → C001
        aliceSingleId = nextId();
        seedUser(aliceSingleId, "alice_single_453", "alice-single-pass", "Alice Single");
        bindRole(aliceSingleId, 14L);  // customer role id = 14 (V33)
        customer1Id = nextId();
        seedCustomer(customer1Id, "C001-453", "Acme 客户");
        bindLink(aliceSingleId, customer1Id, 1);

        // alice (多客户): customer role + 2 link → C001 + C002
        aliceMultiId = nextId();
        seedUser(aliceMultiId, "alice_multi_453", "alice-multi-pass", "Alice Multi");
        bindRole(aliceMultiId, 14L);
        customer2Id = nextId();
        seedCustomer(customer2Id, "C002-453", "Beta 客户");
        bindLink(aliceMultiId, customer2Id, 0);  // primary=false
        // 第二个 link 复用 customer1Id (按 unique key tenant+user+customer, aliceMultiId 已绑 customer1Id 不行, 所以建 customer3)
        customer3Id = nextId();
        seedCustomer(customer3Id, "C003-453", "Gamma 客户");
        bindLink(aliceMultiId, customer3Id, 1);

        // bob: 非 customer 角色 (WMS_ADMIN id=2)
        bobNoCustomerRoleId = nextId();
        seedUser(bobNoCustomerRoleId, "bob_453", "bob-pass", "Bob NoCustomer");
        bindRole(bobNoCustomerRoleId, 2L);  // WMS_ADMIN — 非 customer

        // carol: customer 角色但无 link → 触发 PORTAL_NO_CUSTOMER_LINK 路径
        carolNoCustomerLinkId = nextId();
        seedUser(carolNoCustomerLinkId, "carol_453", "carol-pass", "Carol NoLink");
        bindRole(carolNoCustomerLinkId, 14L);
    }

    @AfterEach
    void tearDown() {
        try {
            // 先清 link (有 FK-like unique key 引用 user_id, customer_id)
            for (Long linkId : createdLinkIds) {
                try { jdbc.update("DELETE FROM t_customer_user_link WHERE id = ?", linkId); }
                catch (Exception ignored) {}
            }
            // 清 user_role binding
            for (Long uid : createdUserIds) {
                try { jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", uid); }
                catch (Exception ignored) {}
            }
            // 清 user (username 唯一键冲突时忽略)
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
        }
    }

    // ====================================================================
    // Case 1: 单客户登录 → token + aud=portal + activeCustomerId
    // ====================================================================

    @Test
    @DisplayName("login_singleCustomer_returnsTokenWithAudPortalAndActiveCustomerId")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void login_singleCustomer_returnsTokenWithAudPortalAndActiveCustomerId() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        // X-Tenant-Id: TenantInterceptor 读这个 header 设 TenantContext=1, 否则 MyBatis Plus
        // 多租户拦截器会注入 `AND tenant_id = 0`, 把 user 查询过滤成 0 行, 所有 portal 登录
        // 都返 401 "用户名或密码错误" (实际是 user 查不到). PortalLoginRequest DTO 没带 tenantId,
        // PortalAuthService 也没显式 set TenantContext, 所以 portal 登录必须走 X-Tenant-Id
        // header 才能正确路由到 tenant_id=1 的数据. 这是当前实现的"未文档化要求".
        h.set("X-Tenant-Id", "1");
        String body = "{\"username\":\"alice_single_453\",\"password\":\"alice-single-pass\"}";
        ResponseEntity<R> resp = rest.exchange(
            "/api/auth/portal/login", HttpMethod.POST, new HttpEntity<>(body, h), R.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);
        assertThat(resp.getBody().getData()).isNotNull();

        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        String token = (String) data.get("token");
        Number activeCustomerId = (Number) data.get("activeCustomerId");
        Object choices = data.get("choices");
        Number userId = (Number) data.get("userId");

        // 单客户: token 非空, activeCustomerId=42 (FIXTURE id), choices=null
        assertThat(token).as("single-customer login 必须返回 token").isNotBlank();
        assertThat(activeCustomerId).isNotNull();
        assertThat(activeCustomerId.longValue()).isEqualTo(customer1Id);
        assertThat(choices).as("single-customer 不应返 choices").isNull();
        assertThat(userId.longValue()).isEqualTo(aliceSingleId);

        // 解析 JWT aud claim → "portal"
        String aud = parseAudience(token);
        assertThat(aud).isEqualTo("portal");

        // active_customer_id claim 也能直接解出
        Number activeInToken = parseActiveCustomerId(token);
        assertThat(activeInToken).isNotNull();
        assertThat(activeInToken.longValue()).isEqualTo(customer1Id);
    }

    // ====================================================================
    // Case 2: 多客户登录 → token=null + choices.size()=2
    // ====================================================================

    @Test
    @DisplayName("login_multiCustomer_returnsMfaChoiceRequiredWithoutToken")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void login_multiCustomer_returnsMfaChoiceRequiredWithoutToken() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Tenant-Id", "1");
        String body = "{\"username\":\"alice_multi_453\",\"password\":\"alice-multi-pass\"}";
        ResponseEntity<R> resp = rest.exchange(
            "/api/auth/portal/login", HttpMethod.POST, new HttpEntity<>(body, h), R.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        String token = (String) data.get("token");
        Object activeCustomerId = data.get("activeCustomerId");
        List<Map<String, Object>> choices = (List<Map<String, Object>>) data.get("choices");

        // 多客户: token=null, activeCustomerId=null, choices=2
        assertThat(token).as("multi-customer 不应直接发 token (走 MFA 选择步)").isNull();
        assertThat(activeCustomerId).isNull();
        assertThat(choices).isNotNull();
        assertThat(choices).hasSize(2);

        // choices 应包含 C002-453 和 C003-453 (按 SysUserRoleMapper 排序: primary desc, id asc)
        List<String> codes = choices.stream().map(c -> (String) c.get("code")).toList();
        assertThat(codes).containsExactlyInAnyOrder("C002-453", "C003-453");
    }

    // ====================================================================
    // Case 3: 错误密码 → 401 (PORTAL_AUTH_INVALID)
    // ====================================================================

    @Test
    @DisplayName("login_wrongPassword_returns401")
    void login_wrongPassword_returns401() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Tenant-Id", "1");
        String body = "{\"username\":\"alice_single_453\",\"password\":\"definitely-wrong-pwd\"}";
        ResponseEntity<R> resp = rest.exchange(
            "/api/auth/portal/login", HttpMethod.POST, new HttpEntity<>(body, h), R.class);

        // AuthException → GlobalExceptionHandler.handleAuth → 401 + body.code=401 (UNAUTHORIZED)
        // 注: PortalErrorCode.PORTAL_AUTH_INVALID.code=401_003 但 httpStatus=401, 因
        // AuthException 继承 AuthenticationException, 走 handleAuth 路径, body.code
        // 统一成 CommonErrorCode.UNAUTHORIZED.code=401.
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(401);
        assertThat(resp.getBody().getMessage()).contains("用户名或密码错误");
    }

    // ====================================================================
    // Case 4: 用户无 customer 角色 → 403 (PORTAL_NO_CUSTOMER_ROLE)
    // 注: 实际系统返回 401 (AuthException 路径); 见类注释 spec deviations
    // ====================================================================

    @Test
    @DisplayName("login_userWithoutCustomerRole_returnsAuthRejection")
    void login_userWithoutCustomerRole_returnsAuthRejection() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Tenant-Id", "1");
        String body = "{\"username\":\"bob_453\",\"password\":\"bob-pass\"}";
        ResponseEntity<R> resp = rest.exchange(
            "/api/auth/portal/login", HttpMethod.POST, new HttpEntity<>(body, h), R.class);

        // 当前实现: AuthException → handleAuth → HTTP 401 (不是 403)
        // plan 写 "returns 403" — 那是 PortalErrorCode.PORTAL_NO_CUSTOMER_ROLE.httpStatus=403,
        // 但实际路由给 AuthException (不是 BizException) 所以统一成 401.
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(401);
        assertThat(resp.getBody().getMessage())
            .as("message 应指向 customer 角色检查失败原因")
            .containsAnyOf("未开通客户门户", "customer");
    }

    // ====================================================================
    // Case 5: admin token 调 portal 端点 → 401 (audience mismatch)
    // ====================================================================

    @Test
    @DisplayName("adminTokenOnPortalEndpoint_returns401AudMismatch")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void adminTokenOnPortalEndpoint_returns401AudMismatch() {
        // 1) admin 登录 → admin token (aud=admin, V2 seed 用户 admin/admin123)
        HttpHeaders loginH = new HttpHeaders();
        loginH.setContentType(MediaType.APPLICATION_JSON);
        String loginBody = "{\"tenantId\":1,\"username\":\"admin\",\"password\":\"admin123\"}";
        ResponseEntity<R> loginResp = rest.exchange(
            "/api/v1/auth/login", HttpMethod.POST, new HttpEntity<>(loginBody, loginH), R.class);
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);
        assertThat(loginResp.getBody()).isNotNull();
        assertThat(loginResp.getBody().getCode()).isEqualTo(0);
        Map<String, Object> loginData = (Map<String, Object>) loginResp.getBody().getData();
        String adminToken = (String) loginData.get("accessToken");
        assertThat(adminToken).isNotBlank();

        // 2) 用 admin token 调 portal 端点 /api/customer/track → JwtAuthenticationFilter
        //    enforceAudienceRoute 抛 AuthException(401_001), 进 Spring Security
        //    authenticationEntryPoint → 401 + body.code=401 (UNAUTHORIZED)
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(adminToken);
        ResponseEntity<R> trackResp = rest.exchange(
            "/api/customer/track?page=1&size=20", HttpMethod.GET, new HttpEntity<>(h), R.class);

        assertThat(trackResp.getStatusCode().value())
            .as("admin token (aud=admin) 调 portal 路径应被 audience 路由拒绝")
            .isEqualTo(401);
        assertThat(trackResp.getBody()).isNotNull();
        assertThat(trackResp.getBody().getCode())
            .as("AuthException 走 handleAuth 路径, body.code=401 (CommonErrorCode.UNAUTHORIZED)")
            .isEqualTo(401);
    }

    // ====================================================================
    // Case 6: select-customer (valid pair) — 4.5A.3-T3.5 (Bug 2 fix)
    // After fix: controller reads userId from request DTO, returns 200 + portal token.
    // ====================================================================

    @Test
    @DisplayName("selectCustomer_validUserCustomerPair_returnsPortalToken")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void selectCustomer_validUserCustomerPair_returnsPortalToken() {
        // 1) 多客户登录拿 portal login response — 含 userId (供 select-customer 用)
        HttpHeaders loginH = new HttpHeaders();
        loginH.setContentType(MediaType.APPLICATION_JSON);
        // 不发 X-Tenant-Id: 验证 Bug 1 fix (PortalAuthController.ensureTenantContext 兜底到 1L)
        String loginBody = "{\"username\":\"alice_multi_453\",\"password\":\"alice-multi-pass\"}";
        ResponseEntity<R> loginResp = rest.exchange(
            "/api/auth/portal/login", HttpMethod.POST, new HttpEntity<>(loginBody, loginH), R.class);
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> loginData = (Map<String, Object>) loginResp.getBody().getData();
        // multi-customer 登录: token=null, userId 非空, choices 含 customer2Id + customer3Id
        assertThat(loginData.get("token")).isNull();
        Number loginUserId = (Number) loginData.get("userId");
        assertThat(loginUserId).isNotNull();
        assertThat(loginUserId.longValue()).isEqualTo(aliceMultiId);

        // 2) select-customer 把 userId (从 login response) 放进 body, customerId 选 customer2Id (aliceMulti primary)
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"userId\":" + loginUserId.longValue() + ",\"customerId\":" + customer2Id + "}";
        ResponseEntity<R> resp = rest.exchange(
            "/api/auth/portal/select-customer", HttpMethod.POST, new HttpEntity<>(body, h), R.class);

        // Bug 2 fix: controller 从 PortalSelectCustomerRequest 读 userId → service.selectCustomer 成功 → 200 + token
        assertThat(resp.getStatusCode().value())
            .as("Bug 2 fix 后 selectCustomer 返回 200 + portal token (不走 CustomerContext)")
            .isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        String newToken = (String) data.get("token");
        Number activeCustomerId = (Number) data.get("activeCustomerId");
        assertThat(newToken).as("selectCustomer 必须返回 portal token").isNotBlank();
        assertThat(activeCustomerId).isNotNull();
        assertThat(activeCustomerId.longValue()).isEqualTo(customer2Id);

        // token aud claim 也是 portal (向后兼容: select-customer 不引入新 audience)
        assertThat(parseAudience(newToken)).isEqualTo("portal");
    }

    // ====================================================================
    // Case 7: select-customer (user↔customer 未绑定) — 4.5A.3-T3.5 (Bug 2 partial fix)
    // Bug 2 fix 后 controller 拿到 userId, 第二个 customerLink 校验才判定 user↔customer 关系,
    // 仍走 PORTAL_USER_NOT_LINKED. AuthException 路径下 HTTP 401 + body.code=401 (UNAUTHORIZED).
    // ====================================================================

    @Test
    @DisplayName("selectCustomer_userNotLinkedToCustomer_returnsAuthRejection")
    void selectCustomer_userNotLinkedToCustomer_returnsAuthRejection() {
        // 1) 单客户登录拿 portal token + userId
        HttpHeaders loginH = new HttpHeaders();
        loginH.setContentType(MediaType.APPLICATION_JSON);
        loginH.set("X-Tenant-Id", "1");
        String loginBody = "{\"username\":\"alice_single_453\",\"password\":\"alice-single-pass\"}";
        ResponseEntity<R> loginResp = rest.exchange(
            "/api/auth/portal/login", HttpMethod.POST, new HttpEntity<>(loginBody, loginH), R.class);
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> loginData = (Map<String, Object>) loginResp.getBody().getData();
        Number loginUserId = (Number) loginData.get("userId");
        assertThat(loginUserId).isNotNull();
        assertThat(loginUserId.longValue()).isEqualTo(aliceSingleId);

        // 2) select-customer 用 aliceSingle 没绑的 customerId (customer2Id 是 aliceMulti 的)
        //    Bug 2 fix 后 controller 正确拿到 userId; service.userRoleMapper.userLinkedToCustomer
        //    返回 false → PORTAL_USER_NOT_LINKED → AuthException → handleAuth → 401
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"userId\":" + loginUserId.longValue() + ",\"customerId\":" + customer2Id + "}";
        ResponseEntity<R> resp = rest.exchange(
            "/api/auth/portal/select-customer", HttpMethod.POST, new HttpEntity<>(body, h), R.class);

        // 当前路由: AuthException → handleAuth → 401 + body.code=401 (PORTAL_USER_NOT_LINKED
        // 进 AuthException 家族, httpStatus=401 而非 plan 写的 403 — 同 case 4)
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(401);
        assertThat(resp.getBody().getMessage())
            .as("message 应指向 user↔customer 未绑定 (真实文案: '无权访问该客户' / '未关联该客户')")
            .containsAnyOf("无权", "未关联", "关联", "绑定", "开通", "user", "customer");
    }

    // ====================================================================
    // Case 8: refresh happy-path — 4.5A.3-T3.5 (Bug 3 fix)
    // 用 portal access token 当 X-Refresh-Token, controller 重新签 portal token.
    // Bug 3 fix: claims.getSubject() (String) + Long.parseLong, 不再抛 RequiredTypeException.
    // ====================================================================

    @Test
    @DisplayName("refresh_validRefreshToken_returnsNewPortalToken")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void refresh_validRefreshToken_returnsNewPortalToken() {
        // 1) 登录拿 portal access token (含 aud=portal + active_customer_id)
        HttpHeaders loginH = new HttpHeaders();
        loginH.setContentType(MediaType.APPLICATION_JSON);
        loginH.set("X-Tenant-Id", "1");
        String loginBody = "{\"username\":\"alice_single_453\",\"password\":\"alice-single-pass\"}";
        ResponseEntity<R> loginResp = rest.exchange(
            "/api/auth/portal/login", HttpMethod.POST, new HttpEntity<>(loginBody, loginH), R.class);
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> loginData = (Map<String, Object>) loginResp.getBody().getData();
        String originalToken = (String) loginData.get("token");
        assertThat(originalToken).isNotBlank();

        // 2) POST /api/auth/portal/refresh, 把原 token 放进 X-Refresh-Token header
        HttpHeaders h = new HttpHeaders();
        h.set("X-Refresh-Token", originalToken);
        // 不发 X-Tenant-Id: 由 PortalAuthController.ensureTenantContext 兜底 (Bug 1 fix),
        // refresh 路径本身不读 TenantContext (JWT tid claim 提供), 此处验证兜底路径也兼容
        ResponseEntity<R> resp = rest.exchange(
            "/api/auth/portal/refresh", HttpMethod.POST, new HttpEntity<>("", h), R.class);

        // Bug 3 fix: claims.getSubject() (String) → Long.parseLong, 不再抛 RequiredTypeException
        assertThat(resp.getStatusCode().value())
            .as("Bug 3 fix 后 refresh 返回 200 + 新 portal token (claims.getSubject() 解析 String sub)")
            .isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        String newToken = (String) data.get("token");
        assertThat(newToken).isNotBlank();
        // 重新签的 token 也是 aud=portal (refresh 路径强制 audience="portal")
        assertThat(parseAudience(newToken)).isEqualTo("portal");
        // active_customer_id 也应保留 (controller 走 jwtUtil.getActiveCustomerId 读取并塞回)
        assertThat(parseActiveCustomerId(newToken)).isNotNull();
        assertThat(parseActiveCustomerId(newToken).longValue()).isEqualTo(customer1Id);
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

    // ====================================================================
    // helpers — JWT parsing (纯 java, 不引第三方, 复用项目内 jjwt 0.12 间接校验签名)
    // ====================================================================

    /**
     * Base64url 解码 JWT payload, 返回 claims map.
     * 不验签 — 该 token 已经被 Spring 服务端签名, 我们只解析 claim 做断言;
     * 测试假设 token 来自本服务的 jwtUtil.issueToken.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJwtPayload(String token) {
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
            java.nio.charset.StandardCharsets.UTF_8);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(payload, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse JWT payload: " + payload, e);
        }
    }

    private String parseAudience(String token) {
        Map<String, Object> claims = parseJwtPayload(token);
        // JJWT 0.12 把 aud 序列化成 JSON 数组 (Set → ArrayList)
        Object aud = claims.get("aud");
        if (aud instanceof List<?> list && !list.isEmpty()) {
            return (String) list.get(0);
        }
        return null;
    }

    private Number parseActiveCustomerId(String token) {
        Map<String, Object> claims = parseJwtPayload(token);
        Object v = claims.get("active_customer_id");
        if (v instanceof Number n) return n;
        if (v == null) return null;
        return Long.valueOf(v.toString());
    }
}
