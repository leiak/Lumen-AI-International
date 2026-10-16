package com.lumen.integration;

import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.dto.CalendarDtos;
import com.lumen.cs.dto.SlaConfigDtos;
import com.lumen.cs.service.BusinessCalendarService;
import com.lumen.cs.service.SlaConfigService;
import com.lumen.cs.service.SlaEventService;
import com.lumen.cs.service.impl.ExceptionSlaAggregationService;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
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

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CS 异常工单 SLA 视角列表 — controller wire contract IT (Iteration 4.2 PR6 finalize).
 *
 * <p>4 个场景:
 * <ul>
 *   <li>{@code happyPath_returnsExceptionSlaViewWithAllFields} — 插入一条 DAMAGE/OPEN exception →
 *       insert L1_RESPONSE_DUE + L1_RESPONDED sla_events → GET /api/v1/cs/exceptions →
 *       校验响应 records[0] 是 {@code ExceptionSlaView} (而非 raw row),
 *       字段 ({@code exceptionId/currentLevel/severity/businessMinutesElapsed/resolveTargetMinutes/escalationStatus/slaEvents})
 *       全部按预期填齐.</li>
 *   <li>{@code pagingAndQueryParams_matchFeContract} — 用 {@code current=1&size=10&status=OPEN}
 *       拉一次, 校验 PageResult.records 是 1 个 ExceptionSlaView (and 总数 ≥ 1), controller
 *       走通了 "FE 发的 current/size/status 三个参数都能被 controller 接受".</li>
 *   <li>{@code severityHighAndLowFilter_reverseMapsToTypes} — 拿 cs_manager 登录 →
 *       GET {@code ?severity=HIGH} → 只剩 DAMAGE 行; 同样 {@code ?severity=LOW}
 *       只剩 ADDR_ERR 行; {@code ?severity=NOT_A_REAL_SEVERITY} 应返空 0 total
 *       (反查反向映射).</li>
 *   <li>{@code noPermCsExceptionList_returns401or403} — 反向断言: 不登录 / 用没有
 *       {@code cs:exception:view} perm 的角色调用, 应 401 或 403, 印证
 *       {@code @PreAuthorize("hasAuthority('cs:exception:view')")} 真生效 (与
 *       V25 seed 角色绑定一致).</li>
 * </ul>
 *
 * <p><b>Tenant 选择:</b> 用 {@code TEST_TENANT = 1L} (cs_manager 测试用户已 seed 在
 * tenant=1, JWT 走 {@code tenantId=1}, controller 查 tenant=1). 与
 * {@code CsCrossModuleIT#csManagerHttp_canCallDispatchExceptionList} 同模式 — 用
 * SQL 直接 seed 测试用户 (不走 {@code UserService}).
 *
 * <p>数据隔离: 本 IT 插入的 exception 用 {@code description = "IT 4_2_FINAL_<uuid>"} 标记,
 * 每条 row 在 {@code @AfterEach} 按 id 删除, 不会污染其他 IT.
 */
@DisplayName("CS 异常工单列表 wire contract IT (4.2 PR6 finalize)")
class CsExceptionListIT extends IntegrationBase {

    @Autowired private SlaConfigService slaConfigService;
    @Autowired private BusinessCalendarService businessCalendarService;
    @Autowired private SlaEventService slaEventService;
    @Autowired private WmsDeliveryExceptionMapper dispatchExceptionMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    private static final long TEST_TENANT = 1L;

    // IT 用 cs_manager 用户 — 持有 cs:exception:view (V25 角色绑定 id 274 → perm 127).
    private static final String CS_MGR_USERNAME = "cs_mgr_list_it_4_2";
    private static final String CS_MGR_PASSWORD = "cs-mgr-pass-list-2026";
    private static final String NOVIEW_USERNAME = "no_view_user_4_2";
    private static final String NOVIEW_PASSWORD = "no-view-pass-2026";

    /** 本 IT instance 标记 — 每条 exception 的 description 前缀, 保证不会跟其他 IT 重复. */
    private final String runTag = "IT_4_2_FINAL_" + UUID.randomUUID().toString().substring(0, 8);

    /**
     * 找到「最近一个工作日上午 10:00」 — 保证 createdAt 在 Mon-Fri 9-18 区间内,
     * 否则 {@link com.lumen.cs.calculator.SlaCalculator} 算出 elapsed=0 (业务时间
     * 整体跳过). 测试要断言 elapsed > resolveMinutes=30, 必须把 createdAt 钉在
     * 一个工作时段内.
     */
    private static LocalDateTime recentWeekdayMorning(int daysBack) {
        for (int i = 1; i <= daysBack + 7; i++) {
            LocalDateTime candidate = LocalDateTime.now().minusDays(i)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
            java.time.DayOfWeek dow = candidate.getDayOfWeek();
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                return candidate;
            }
        }
        // 极边角: 7 天都是周末 (不可能)
        return LocalDateTime.now().minusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private final Set<Long> createdExceptionIds = new HashSet<>();
    private Long csMgrUserId;
    private Long noViewUserId;

    @BeforeEach
    void setUp() {
        TenantContext.set(TEST_TENANT);
        cleanSlaEventsForTenant(TEST_TENANT);

        for (int weekday = 1; weekday <= 5; weekday++) {
            try {
                businessCalendarService.create(TEST_TENANT,
                    new CalendarDtos.CreateRequest(
                        weekday, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0)));
            } catch (Exception ignored) {
                // already exists
            }
        }
        // seed SLA configs (idempotent; 已有则跳过)
        seedSlaConfigs();

        csMgrUserId = seedCsManagerUserIfAbsent();
        noViewUserId = seedNoViewUserIfAbsent();
    }

    @AfterEach
    void tearDown() {
        try {
            for (Long id : createdExceptionIds) {
                try { dispatchExceptionMapper.deleteById(id); } catch (Exception ignored) {}
            }
            // 清本 IT 留下的 sla_event 行 (按描述前缀找也容易; 这里按 tenant 删干净)
            cleanSlaEventsForTenant(TEST_TENANT);
            // 清掉测试用户 + 角色绑定 (按 user_id 反查 FK)
            try {
                jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (?, ?)",
                    csMgrUserId, noViewUserId);
                jdbc.update("DELETE FROM sys_user WHERE id IN (?, ?)",
                    csMgrUserId, noViewUserId);
            } catch (Exception ignored) {}
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================
    // Case 1: happy path — ExceptionSlaView 全字段
    // =========================================================

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void happyPath_returnsExceptionSlaViewWithAllFields() {
        // 1) 插入一条 DAMAGE/OPEN exception, handler=null → inferLevel=L1
        //    createdAt 必须落在业务时段 (Mon-Fri 9-18), 否则 elapsed=0.
        LocalDateTime createdAt = recentWeekdayMorning(3);
        long exId = insertException("DAMAGE", "OPEN", null, createdAt);
        createdExceptionIds.add(exId);

        // 2) 模拟 listener 落 2 条 sla_event: L1_RESPONSE_DUE + L1_RESPONDED
        //    都用业务时段内的时间, 否则 SlaCalculator 取默认 createdAt 而不是 responded.
        slaEventService.record(TEST_TENANT, exId, "L1_RESPONSE_DUE", "L1", "HIGH",
            createdAt, null);
        slaEventService.record(TEST_TENANT, exId, "L1_RESPONDED", "L1", "HIGH",
            createdAt.plusHours(1), null);

        // 3) cs_manager 登录 + GET 列表
        String token = loginAndGetToken(CS_MGR_USERNAME, CS_MGR_PASSWORD, TEST_TENANT);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<R> resp = rest.exchange(
            "/api/v1/cs/exceptions?current=1&size=50", HttpMethod.GET,
            new HttpEntity<>(h), R.class);

        assertThat(resp.getStatusCode().value())
            .as("cs_manager → 200 (有 cs:exception:view, controller 已简化为单值 hasAuthority)")
            .isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode())
            .as("R.code 应为 0, 不是 10003 (FORBIDDEN)")
            .isEqualTo(0);

        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertThat(records).as("records 不应为空 (本 IT 至少插了 1 条)").isNotEmpty();

        // 找到本 IT 插入的那条 (用 exId 匹配)
        Map<String, Object> row = records.stream()
            .filter(r -> ((Number) r.get("exceptionId")).longValue() == exId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("ExceptionSlaView records 缺少 exId=" + exId));

        // 4) 校验响应 shape 是 ExceptionSlaView (字段名匹配 EscalationDtos record)
        assertThat(row)
            .as("ExceptionSlaView shape 应包含 FE 渲染的全部字段")
            .containsKeys("exceptionId", "type", "status", "handler",
                "currentLevel", "severity",
                "businessMinutesElapsed", "resolveTargetMinutes",
                "escalationStatus", "slaEvents");

        assertThat(row.get("type")).isEqualTo("DAMAGE");
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("severity")).isEqualTo("HIGH");
        assertThat(row.get("currentLevel")).isEqualTo("L1");
        // 配了 SLA config: resolve_minutes=30 (HIGH L1) → 45 分钟前已 BREACHED
        assertThat(((Number) row.get("resolveTargetMinutes")).longValue()).isEqualTo(30L);
        assertThat(((Number) row.get("businessMinutesElapsed")).longValue())
            .as("elapsed 必须为正 (业务时间已超过 30 min)")
            .isPositive();
        assertThat(row.get("escalationStatus"))
            .as("elapsed > 30min resolve target → BREACHED")
            .isEqualTo("BREACHED");

        // slaEvents 列表应有 2 条 (按 occurredAt ASC, Jackson 默认进 list 顺序)
        List<Map<String, Object>> events = (List<Map<String, Object>>) row.get("slaEvents");
        assertThat(events).as("slaEvents 应有 2 条 (L1_RESPONSE_DUE + L1_RESPONDED)").hasSize(2);
        assertThat(events.get(0).get("eventType")).isEqualTo("L1_RESPONSE_DUE");
        assertThat(events.get(1).get("eventType")).isEqualTo("L1_RESPONDED");
    }

    // =========================================================
    // Case 2: paging + status filter
    // =========================================================

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void pagingAndQueryParams_matchFeContract() {
        // 插入两条 (1 OPEN + 1 RESOLVED, 不同 type)
        long openId = insertException("DAMAGE", "OPEN", null,
            LocalDateTime.now().minusMinutes(10));
        long resolvedId = insertException("REJECTED", "RESOLVED", "cs_supervisor_1",
            LocalDateTime.now().minusMinutes(20));
        createdExceptionIds.add(openId);
        createdExceptionIds.add(resolvedId);

        String token = loginAndGetToken(CS_MGR_USERNAME, CS_MGR_PASSWORD, TEST_TENANT);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);

        // 2.1 status=OPEN filter → 必须包含我们的 openId (可能其他 IT 也有 OPEN, 但至少 1 条)
        ResponseEntity<R> openResp = rest.exchange(
            "/api/v1/cs/exceptions?current=1&size=50&status=OPEN", HttpMethod.GET,
            new HttpEntity<>(h), R.class);
        Map<String, Object> openData = (Map<String, Object>) openResp.getBody().getData();
        List<Map<String, Object>> openRecords = (List<Map<String, Object>>) openData.get("records");
        assertThat(openRecords.stream()
            .anyMatch(r -> ((Number) r.get("exceptionId")).longValue() == openId))
            .as("status=OPEN filter: 含本 IT 的 OPEN ex (exId=%d)", openId)
            .isTrue();
        // resolvedId 不应在 OPEN 列表里
        assertThat(openRecords.stream()
            .noneMatch(r -> ((Number) r.get("exceptionId")).longValue() == resolvedId))
            .as("status=OPEN filter: 不应含 RESOLVED ex (exId=%d)", resolvedId)
            .isTrue();

        // 2.2 PageResult current/size round-trip
        assertThat(((Number) openData.get("pageNum")).longValue()).isEqualTo(1L);
        assertThat(((Number) openData.get("pageSize")).longValue()).isEqualTo(50L);

        // 2.3 不带 status filter → 两条都返 (用 exId 匹配找本 IT 两条)
        ResponseEntity<R> allResp = rest.exchange(
            "/api/v1/cs/exceptions?current=1&size=50", HttpMethod.GET,
            new HttpEntity<>(h), R.class);
        List<Map<String, Object>> allRecords = (List<Map<String, Object>>)
            ((Map<String, Object>) allResp.getBody().getData()).get("records");
        Map<String, Object> resolvedRow = allRecords.stream()
            .filter(r -> ((Number) r.get("exceptionId")).longValue() == resolvedId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("records 应含 resolvedId=" + resolvedId));
        // REJECTED → MEDIUM severity (PR6 反馈: 高内聚派生)
        assertThat(resolvedRow.get("severity")).isEqualTo("MEDIUM");
        assertThat(resolvedRow.get("currentLevel")).isEqualTo("L2");
    }

    // =========================================================
    // Case 3: severity filter 反向映射
    // =========================================================

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void severityHighAndLowFilter_reverseMapsToTypes() {
        // 1 DAMAGE (HIGH), 1 REJECTED (MEDIUM), 1 ADDR_ERR (LOW), 1 OVERTIME (MEDIUM)
        long damageId    = insertException("DAMAGE",   "OPEN", null,
            LocalDateTime.now().minusMinutes(10));
        long rejectedId  = insertException("REJECTED", "OPEN", null,
            LocalDateTime.now().minusMinutes(11));
        long addrErrId   = insertException("ADDR_ERR", "OPEN", null,
            LocalDateTime.now().minusMinutes(12));
        long overtimeId  = insertException("OVERTIME", "OPEN", null,
            LocalDateTime.now().minusMinutes(13));
        createdExceptionIds.add(damageId);
        createdExceptionIds.add(rejectedId);
        createdExceptionIds.add(addrErrId);
        createdExceptionIds.add(overtimeId);

        String token = loginAndGetToken(CS_MGR_USERNAME, CS_MGR_PASSWORD, TEST_TENANT);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);

        // 3.1 severity=HIGH → 列表必含 damageId (可含其他 tenant=1 HIGH, 但本 IT 那条必须有)
        List<Map<String, Object>> highRecords = fetchList(token, "severity=HIGH");
        assertThat(highRecords.stream()
            .anyMatch(r -> ((Number) r.get("exceptionId")).longValue() == damageId))
            .as("severity=HIGH 必须含 DAMAGE ex (exId=%d)", damageId)
            .isTrue();
        // LOW + MEDIUM 的不该出现在 HIGH
        assertThat(highRecords.stream()
            .noneMatch(r -> ((Number) r.get("exceptionId")).longValue() == addrErrId))
            .as("severity=HIGH 不应含 ADDR_ERR ex (exId=%d)", addrErrId)
            .isTrue();

        // 3.2 severity=LOW → 仅 ADDR_ERR (本 IT 那条必须有)
        List<Map<String, Object>> lowRecords = fetchList(token, "severity=LOW");
        assertThat(lowRecords.stream()
            .anyMatch(r -> ((Number) r.get("exceptionId")).longValue() == addrErrId))
            .as("severity=LOW 必须含 ADDR_ERR ex (exId=%d)", addrErrId)
            .isTrue();
        assertThat(lowRecords.stream()
            .noneMatch(r -> ((Number) r.get("exceptionId")).longValue() == damageId))
            .as("severity=LOW 不应含 DAMAGE ex (exId=%d)", damageId)
            .isTrue();

        // 3.3 severity=MEDIUM → REJECTED + OVERTIME 都该出现
        List<Map<String, Object>> medRecords = fetchList(token, "severity=MEDIUM");
        assertThat(medRecords.stream()
            .anyMatch(r -> ((Number) r.get("exceptionId")).longValue() == rejectedId))
            .as("severity=MEDIUM 必须含 REJECTED ex (exId=%d)", rejectedId)
            .isTrue();
        assertThat(medRecords.stream()
            .anyMatch(r -> ((Number) r.get("exceptionId")).longValue() == overtimeId))
            .as("severity=MEDIUM 必须含 OVERTIME ex (exId=%d)", overtimeId)
            .isTrue();
        assertThat(medRecords.stream()
            .noneMatch(r -> ((Number) r.get("exceptionId")).longValue() == damageId))
            .as("severity=MEDIUM 不应含 DAMAGE ex (exId=%d)", damageId)
            .isTrue();
        assertThat(medRecords.stream()
            .noneMatch(r -> ((Number) r.get("exceptionId")).longValue() == addrErrId))
            .as("severity=MEDIUM 不应含 ADDR_ERR ex (exId=%d)", addrErrId)
            .isTrue();

        // 3.4 severity=CRITICAL (非枚举值) → 0 条 (controller 短路返空)
        List<Map<String, Object>> none = fetchList(token, "severity=CRITICAL");
        assertThat(none).as("severity=CRITICAL (未映射) → 空 (短路防 500)").isEmpty();
    }

    // =========================================================
    // Case 4: 反向鉴权 (没 perm 的用户不能调)
    // =========================================================

    @Test
    void noPermUser_returns401or403_andUnauth_returns401() {
        // 4.1 没 token → 401
        ResponseEntity<R> noToken = rest.exchange(
            "/api/v1/cs/exceptions?current=1&size=20", HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()), R.class);
        assertThat(noToken.getStatusCode().value())
            .as("无 token → 401")
            .isEqualTo(401);

        // 4.2 拿到 token 但无 cs:exception:view perm 的用户 → 403 (或 401, 取决于 Spring Security 配置)
        String noViewToken = loginAndGetToken(NOVIEW_USERNAME, NOVIEW_PASSWORD, TEST_TENANT);
        HttpHeaders authH = new HttpHeaders();
        authH.setBearerAuth(noViewToken);
        ResponseEntity<R> noViewResp = rest.exchange(
            "/api/v1/cs/exceptions?current=1&size=20", HttpMethod.GET,
            new HttpEntity<>(authH), R.class);
        assertThat(noViewResp.getStatusCode().value())
            .as("无 cs:exception:view 用户 → 4xx (印证 controller hasAuthority 'cs:exception:view')")
            .isIn(401, 403);
    }

    // =========================================================
    // helpers
    // =========================================================

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> fetchList(String token, String extraQuery) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<R> resp = rest.exchange(
            "/api/v1/cs/exceptions?current=1&size=200&" + extraQuery, HttpMethod.GET,
            new HttpEntity<>(h), R.class);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        return (List<Map<String, Object>>) data.get("records");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String loginAndGetToken(String username, String password, long tenantId) {
        HttpHeaders loginH = new HttpHeaders();
        loginH.setContentType(MediaType.APPLICATION_JSON);
        String loginBody = "{\"tenantId\":" + tenantId
            + ",\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        ResponseEntity<R> loginResp = rest.exchange(
            "/api/v1/auth/login", HttpMethod.POST, new HttpEntity<>(loginBody, loginH), R.class);
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> loginData = (Map<String, Object>) loginResp.getBody().getData();
        return (String) loginData.get("accessToken");
    }

    private void seedSlaConfigs() {
        // 把 resolveMinutes 都调小 (30 min), 测 BREACHED 分支方便
        tryCreateConfig("HIGH",   "L1", 5, 30, "L2");
        tryCreateConfig("HIGH",   "L2", 5, 60, "L3");
        tryCreateConfig("HIGH",   "L3", 5, 120, null);
        tryCreateConfig("MEDIUM", "L1", 30, 120, "L2");
        tryCreateConfig("MEDIUM", "L2", 30, 240, "L3");
        tryCreateConfig("MEDIUM", "L3", 30, 480, null);
        tryCreateConfig("LOW",    "L1", 60, 360, "L2");
        tryCreateConfig("LOW",    "L2", 60, 720, "L3");
        tryCreateConfig("LOW",    "L3", 60, 1440, null);
    }

    private void tryCreateConfig(String severity, String level, int responseMin, int resolveMin, String escalateTo) {
        try {
            slaConfigService.create(TEST_TENANT, new SlaConfigDtos.CreateRequest(
                severity, level, responseMin, resolveMin, escalateTo));
        } catch (Exception e) {
            // already exists
        }
    }

    private long insertException(String type, String status, String handler, LocalDateTime createdAt) {
        WmsDeliveryException ex = new WmsDeliveryException();
        ex.setTenantId(TEST_TENANT);
        ex.setOrderId(99L);
        ex.setType(type);
        ex.setStatus(status);
        ex.setDescription(runTag + ":" + type + ":" + status);
        ex.setHandler(handler);
        ex.setCreatedAt(createdAt);
        dispatchExceptionMapper.insert(ex);
        return ex.getId();
    }

    private void cleanSlaEventsForTenant(long tenantId) {
        try {
            jdbc.update("DELETE FROM t_sla_event WHERE tenant_id = ?", tenantId);
        } catch (Exception ignored) {
            // best effort
        }
    }

    /**
     * Upsert cs_manager 测试用户 (tenant=1) — 来自 V25 角色, 持有 cs:exception:view + 4 个 cs:* 权限.
     * 返回 sys_user.id.
     */
    private Long seedCsManagerUserIfAbsent() {
        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM sys_user WHERE tenant_id = 1 AND username = ?",
            Long.class, CS_MGR_USERNAME);
        String hash = passwordEncoder.encode(CS_MGR_PASSWORD);
        long userId;
        if (!ids.isEmpty()) {
            userId = ids.get(0);
            jdbc.update(
                "UPDATE sys_user SET password_hash = ?, status = 1, deleted = 0 WHERE id = ?",
                hash, userId);
        } else {
            userId = 900_002L;
            jdbc.update(
                "INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, version, deleted) "
                    + "VALUES (?, 1, ?, ?, 'IT cs_manager list', 1, 0, 0)",
                userId, CS_MGR_USERNAME, hash);
        }
        // bind role_id=10 (cs_manager) — V25 seed
        try {
            int rolePermPk = (int) (Math.abs(System.nanoTime()) % 2_000_000_000);
            jdbc.update(
                "INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, created_at) "
                    + "VALUES (?, ?, 10, 1, NOW())",
                rolePermPk, userId);
        } catch (Exception dup) {
            // already bound
        }
        return userId;
    }

    /**
     * Upsert 一个没有任何 cs:* 或 dispatch:* perm 的测试用户 — 用于 Case 4 反向鉴权.
     */
    private Long seedNoViewUserIfAbsent() {
        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM sys_user WHERE tenant_id = 1 AND username = ?",
            Long.class, NOVIEW_USERNAME);
        String hash = passwordEncoder.encode(NOVIEW_PASSWORD);
        long userId;
        if (!ids.isEmpty()) {
            userId = ids.get(0);
            jdbc.update(
                "UPDATE sys_user SET password_hash = ?, status = 1, deleted = 0 WHERE id = ?",
                hash, userId);
        } else {
            userId = 900_003L;
            jdbc.update(
                "INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, version, deleted) "
                    + "VALUES (?, 1, ?, ?, 'IT no-view', 1, 0, 0)",
                userId, NOVIEW_USERNAME, hash);
        }
        // 不绑任何角色 — 这样该用户无任何 perm, 调 protected API 应 401/403.
        return userId;
    }

    /** 静态 helper — 推导 severity 反查 (留给 reviewer 参考). */
    @SuppressWarnings("unused")
    private static String derive(String type) {
        return ExceptionSlaAggregationService.deriveSeverity(type);
    }
}
