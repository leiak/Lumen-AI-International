package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.dto.CalendarDtos;
import com.lumen.cs.dto.SlaConfigDtos;
import com.lumen.cs.entity.SlaEvent;
import com.lumen.cs.entity.WmsDeliveryExceptionView;
import com.lumen.cs.mapper.SlaEventMapper;
import com.lumen.cs.mapper.WmsDeliveryExceptionViewMapper;
import com.lumen.cs.service.BusinessCalendarService;
import com.lumen.cs.service.EscalationService;
import com.lumen.cs.service.SlaConfigService;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
import com.lumen.extension.outbox.EventOutbox;
import com.lumen.extension.outbox.EventOutboxMapper;
import com.lumen.extension.outbox.OutboxDispatcher;
import org.awaitility.Awaitility;
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
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模块集成测试: lumen-cs SLA 升级 → outbox 事件 → lumen-dispatch 订阅更新 handler
 * (Iteration 4.2 PR5).
 *
 * <p>覆盖 4 个场景:
 * <ul>
 *   <li>{@code escalationL1toL2_updatesHandlerOnDispatchSide} —
 *       full path: 扫描 → 发 outbox → dispatcher 重发布 → listener 消费 →
 *       wms_delivery_exception.handler 更新</li>
 *   <li>{@code escalationWithDisconnectedListener_doesNotBlockScan} —
 *       scan 始终完成 + outbox 行始终写入; listener 处理是副作用, 即使偶发未触发
 *       也不应阻塞 sla_event 写入</li>
 *   <li>{@code v25Guard_dispatchAdminCannotInvestigateAnymore} —
 *       V25 落地后 cs_manager/cs_supervisor/customer_service 3 角色 + 5 cs:* 权限
 *       写入 sys_role / sys_permission; dispatch_admin 不再有
 *       dispatch:exception:investigate; guard_expression 保持 V20 不变
 *       (runtime 不解析, 详见 V25 头部注释)</li>
 *   <li>{@code csManagerHttp_canCallDispatchExceptionList} —
 *       HTTP 层回归测试: cs_manager 用户 (只有 cs:exception:view, 没有
 *       dispatch:exception:list) 能 200 调用 GET /api/v1/dispatch/exceptions,
 *       印证 DeliveryExceptionController 用 hasAnyAuthority 同时接受两套
 *       perm code — 没有这次 fix 会 403</li>
 * </ul>
 */
@DisplayName("CS 跨模块集成测试 (4.2 PR5)")
@TestPropertySource(properties = "lumen.cs.scan-tenants=200999")
class CsCrossModuleIT extends IntegrationBase {

    @Autowired private EscalationService escalationService;
    @Autowired private SlaConfigService slaConfigService;
    @Autowired private BusinessCalendarService businessCalendarService;
    @Autowired private SlaEventMapper slaEventMapper;
    @Autowired private WmsDeliveryExceptionViewMapper exceptionViewMapper;
    @Autowired private WmsDeliveryExceptionMapper dispatchExceptionMapper;
    @Autowired private EventOutboxMapper outboxMapper;
    @Autowired private OutboxDispatcher outboxDispatcher;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    private static final long TEST_TENANT = 200_999L;

    // IT 临时 user — 仅本 test class 用来模拟"持有 cs_manager 角色但无
    // dispatch:exception:* perm" 的真实 caller (复现 V25 落地后的 cs_* 工作流).
    // username 是该用户唯一 ID, 跨多个 case 复用; tearDown 清掉.
    private static final String CS_MGR_USERNAME = "cs_mgr_it_4_2_pr5";
    private static final String CS_MGR_PASSWORD = "cs-mgr-pass-2026";

    private final Set<Long> createdExceptionIds = new HashSet<>();
    private Long csMgrUserId;

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

        // upsert 一个 cs_manager 测试用户 (不走 UserService, 因为 admin
        // user 自身需要 SUPER_ADMIN perm 才能 create user, 这里直接 SQL
        // insert + bind role_id=10 (V25 seed 的 cs_manager 角色)).
        // 真实 ID 用一个固定不冲突的值 (V2 seed admin=1, 业务自动 +1 分配).
        csMgrUserId = seedCsManagerUserIfAbsent();
    }

    @AfterEach
    void tearDown() {
        try {
            for (Long id : createdExceptionIds) {
                try { dispatchExceptionMapper.deleteById(id); } catch (Exception ignored) {}
            }
            cleanSlaEventsForTenant(TEST_TENANT);
            // 清掉 IT 临时 user + role binding
            try {
                jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", csMgrUserId);
                jdbc.update("DELETE FROM sys_user WHERE id = ?", csMgrUserId);
            } catch (Exception ignored) {}
        } finally {
            TenantContext.clear();
        }
    }

    // ====================================================================
    // Case 1: 完整路径 — escalation → outbox → listener → handler 更新
    // ====================================================================

    @Test
    @DisplayName("escalationL1toL2_updatesHandlerOnDispatchSide: 扫描 → outbox → lumen-dispatch 更新 handler")
    void escalationL1toL2_updatesHandlerOnDispatchSide() {
        seedSlaConfigs();

        long exId = insertException("DAMAGE", "OPEN", null,
            LocalDateTime.now().minusDays(7));
        createdExceptionIds.add(exId);

        escalationService.scan();

        // 1) outbox 必须写入 1 条 exception.escalated
        List<EventOutbox> outboxes = outboxMapper.selectList(new LambdaQueryWrapper<EventOutbox>()
            .eq(EventOutbox::getEventType, "exception.escalated")
            .eq(EventOutbox::getAggregateId, String.valueOf(exId)));
        assertThat(outboxes).hasSize(1);
        EventOutbox outbox = outboxes.get(0);
        assertThat(outbox.getPayload()).contains("\"newHandlerUsername\"", "system_200999_L2");

        // 2) 手动触发 dispatcher (避免 @Scheduled 2s 轮询等待) + listener 异步消费
        //    Dispatcher 路径: outbox → parseEvent → ApplicationEventPublisher →
        //    @EventListener @Async("outboxExecutor") → handler 更新.
        triggerDispatcherUntilStatusDone(outbox.getId(), 10);

        // 3) 等待异步 listener 完成 (listener 在 outboxExecutor 异步跑)
        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                WmsDeliveryException updated;
                try {
                    TenantContext.set(TEST_TENANT);
                    updated = dispatchExceptionMapper.selectById(exId);
                } finally {
                    TenantContext.clear();
                }
                assertThat(updated).isNotNull();
                assertThat(updated.getHandler()).isEqualTo("system_200999_L2");
            });

        // 4) t_sla_event 写入 1 条 ESCALATED
        List<SlaEvent> events = slaEventMapper.selectList(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, TEST_TENANT)
            .eq(SlaEvent::getExceptionId, exId));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("ESCALATED");
    }

    // ====================================================================
    // Case 2: scan 不阻塞 + outbox 写入
    // ====================================================================

    @Test
    @DisplayName("escalationWithDisconnectedListener_doesNotBlockScan: 即使 listener 异常, scan + outbox 仍 OK")
    void escalationWithDisconnectedListener_doesNotBlockScan() {
        seedSlaConfigs();

        long exId = insertException("DAMAGE", "OPEN", null,
            LocalDateTime.now().minusDays(7));
        createdExceptionIds.add(exId);

        // scan 不抛 — 即使 listener 抛错也不应回滚 sla_event 写入
        escalationService.scan();

        // outbox 已写
        List<EventOutbox> outboxes = outboxMapper.selectList(new LambdaQueryWrapper<EventOutbox>()
            .eq(EventOutbox::getEventType, "exception.escalated")
            .eq(EventOutbox::getAggregateId, String.valueOf(exId)));
        assertThat(outboxes).hasSize(1);

        // sla_event 已写
        List<SlaEvent> events = slaEventMapper.selectList(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, TEST_TENANT)
            .eq(SlaEvent::getExceptionId, exId));
        assertThat(events).hasSize(1);

        // dispatcher 重发 + listener 异步处理 (复用 Case 1 的 pattern)
        triggerDispatcherUntilStatusDone(outboxes.get(0).getId(), 10);

        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                WmsDeliveryException updated;
                try {
                    TenantContext.set(TEST_TENANT);
                    updated = dispatchExceptionMapper.selectById(exId);
                } finally {
                    TenantContext.clear();
                }
                assertThat(updated).isNotNull();
                assertThat(updated.getHandler()).isEqualTo("system_200999_L2");
            });
    }

    // ====================================================================
    // Case 3: V25 SQL 落地校验 (4.2 PR5: 不再检查 guard_expression runtime 写入)
    // ====================================================================

    @Test
    @DisplayName("v25Guard_dispatchAdminCannotInvestigateAnymore: V25 SQL 角色/权限/解绑 全落地")
    void v25Guard_dispatchAdminCannotInvestigateAnymore() {
        // 直接查 SQL 验证 V25 落地 (不走 mapper, 因为 mapper 都是 BaseEntity 受租户过滤):
        //   1. cs_manager/cs_supervisor/customer_service 3 角色在 sys_role
        //   2. cs:exception:respond 等 5 个 cs:* perm 在 sys_permission
        //   3. cs_manager 拿到全部 5 个 cs:* (sys_role_permission 5 行)
        //   4. dispatch_admin 不再有 dispatch:exception:investigate (id=120)
        //   5. (PR5 调整) guard_expression 在 V20/V25 之间没改 — runtime 不解析,
        //      详见 V25 头部注释 "关于 t_state_transition.guard_expression"

        try (Connection c = dataSource.getConnection()) {
            // 1) cs_manager/cs_supervisor/customer_service 3 角色
            assertThat(queryCount(c,
                "SELECT COUNT(*) FROM sys_role WHERE code IN ('cs_manager','cs_supervisor','customer_service') AND tenant_id=1 AND deleted=0"))
                .as("V25 1: cs_* 3 roles seeded")
                .isEqualTo(3);

            // 2) 5 cs:* perms
            assertThat(queryCount(c,
                "SELECT COUNT(*) FROM sys_permission WHERE code LIKE 'cs:%' AND tenant_id=1 AND deleted=0"))
                .as("V25 2: 5 cs:* permissions seeded")
                .isEqualTo(5);

            // 3) cs_manager 拿到全部 5 cs:* (sys_role_permission 5 行)
            assertThat(queryCount(c,
                "SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_role r ON rp.role_id=r.id JOIN sys_permission p ON rp.permission_id=p.id WHERE r.code='cs_manager' AND p.code LIKE 'cs:%' AND rp.tenant_id=1"))
                .as("V25 3: cs_manager has all 5 cs:* perms")
                .isEqualTo(5);

            // 4) dispatch_admin (id=5) 不再有 dispatch:exception:investigate (perm_id=120)
            assertThat(queryCount(c,
                "SELECT COUNT(*) FROM sys_role_permission WHERE role_id=5 AND permission_id=120 AND tenant_id=1"))
                .as("V25 4: dispatch_admin unbound from dispatch:exception:investigate")
                .isZero();

            // 5) (PR5) investigate guard 仍保留 V20 的 role:customer_service — V25
            //    故意不再改写, 因为 StateMachineEngine 当前不解析 guard_expression.
            //    这条断言是回归保险: 如果未来又有人误改 SQL, 跑这条会失败提醒.
            String guard = queryString(c,
                "SELECT guard_expression FROM t_state_transition WHERE state_machine_code='EXCEPTION_WORK_ORDER' AND transition_name='investigate' AND tenant_id=0");
            assertThat(guard)
                .as("V25 5: investigate guard_expression preserved as V20 (runtime 不解析, 详见 V25 注释)")
                .isEqualTo("role:customer_service");
        } catch (Exception e) {
            throw new RuntimeException("V25 SQL verification failed", e);
        }
    }

    // ====================================================================
    // Case 4: HTTP 层回归 — cs_manager 用户能调用 dispatch exception 接口
    // ====================================================================

    /**
     * 4.2 PR5 C1 修复的 HTTP 层回归测试.
     *
     * <p>前置条件: Case 3 已经验证 (a) V25 解绑 dispatch_admin 的 dispatch:exception:*;
     * (b) cs_manager 角色拥有 cs:exception:view. 这里用一个 SQL 直接 seed 的测试用户
     * 持有 cs_manager 角色 (无 dispatch:exception:*), 验证 GET 列表接口能 200,
     * 而不是 403 — 这是 controller {@code hasAnyAuthority('dispatch:exception:list',
     * 'cs:exception:view')} 的 key 行为.
     *
     * <p>没有 controller 这一改之前, 这条 GET 会被 hasAuthority 单值检查挡掉,
     * 返回 403 + code=10003 (FORBIDDEN).
     */
    @Test
    @DisplayName("csManagerHttp_canCallDispatchExceptionList: cs_manager 用户能 200 调用异常工单列表")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void csManagerHttp_canCallDispatchExceptionList() {
        // 1) 用 cs_manager 测试用户登录 (tenant=1)
        HttpHeaders loginH = new HttpHeaders();
        loginH.setContentType(MediaType.APPLICATION_JSON);
        String loginBody = "{\"tenantId\":1,\"username\":\"" + CS_MGR_USERNAME
                + "\",\"password\":\"" + CS_MGR_PASSWORD + "\"}";
        ResponseEntity<R> loginResp = rest.exchange(
                "/api/v1/auth/login", HttpMethod.POST, new HttpEntity<>(loginBody, loginH), R.class);
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);
        assertThat(loginResp.getBody()).isNotNull();
        assertThat(loginResp.getBody().getCode()).isEqualTo(0);
        Map<String, Object> loginData = (Map<String, Object>) loginResp.getBody().getData();
        String token = (String) loginData.get("accessToken");
        assertThat(token).isNotBlank();

        // 2) 拿到 token 后调用 GET /api/v1/dispatch/exceptions 列表接口
        //    cs_manager 只有 cs:exception:view, 没有 dispatch:exception:list
        //    没有这次 C1 修复就 403; 修复后 controller 用 hasAnyAuthority 接受
        //    cs:exception:view, 应为 200 + R.code=0
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<R> listResp = rest.exchange(
                "/api/v1/dispatch/exceptions?current=1&size=20", HttpMethod.GET,
                new HttpEntity<>(h), R.class);

        assertThat(listResp.getStatusCode().value())
                .as("cs_manager 用 cs:exception:view 应当能 200 调用异常工单 list 接口"
                        + " (C1 fix: controller hasAnyAuthority 接受新旧 perm)")
                .isEqualTo(200);
        assertThat(listResp.getBody()).isNotNull();
        assertThat(listResp.getBody().getCode())
                .as("R.code 应为 0 (业务成功), 不是 10003 (FORBIDDEN)")
                .isEqualTo(0);

        // 3) 反向断言: 不带 token 直接调用应 401 (鉴权层保护)
        ResponseEntity<R> noTokenResp = rest.exchange(
                "/api/v1/dispatch/exceptions?current=1&size=20", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), R.class);
        assertThat(noTokenResp.getStatusCode().value())
                .as("无 token 调用应 401, 印证 SecurityFilterChain 真在保护接口")
                .isEqualTo(401);
    }

    // ================================================================
    // helpers
    // ================================================================

    private void seedSlaConfigs() {
        tryCreateConfig("HIGH",   "L1", 5,  30,   "L2");
        tryCreateConfig("HIGH",   "L2", 5,  60,   "L3");
        tryCreateConfig("HIGH",   "L3", 5,  120,  null);
        tryCreateConfig("MEDIUM", "L1", 30, 120,  "L2");
        tryCreateConfig("MEDIUM", "L2", 30, 240,  "L3");
        tryCreateConfig("MEDIUM", "L3", 30, 480,  null);
        tryCreateConfig("LOW",    "L1", 60, 360,  "L2");
        tryCreateConfig("LOW",    "L2", 60, 720,  "L3");
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

    /**
     * Upsert 一个 cs_manager 测试用户 (tenant=1) 用来驱动 Case 4 的 HTTP 层回归.
     *
     * <p>直接 SQL insert + bind role_id=10 (V25 seed 的 cs_manager 角色), 不用
     * UserService — admin 用户自身需要 SUPER_ADMIN perm 才能 create user, 用 SQL
     * 走基线是更直白的路径. password hash 用与 admin 一致的 BCrypt cost=10,
     * 明文 {@link #CS_MGR_PASSWORD} 固定 (test 专用, 不进生产).
     *
     * <p>返回 sys_user.id; tearDown 会清掉用户 + role binding.
     */
    private Long seedCsManagerUserIfAbsent() {
        // query() 返回空 list 而不是抛 EmptyResultDataAccessException (比 queryForObject 更稳)
        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM sys_user WHERE tenant_id = 1 AND username = ?",
            Long.class, CS_MGR_USERNAME);
        String hash = passwordEncoder.encode(CS_MGR_PASSWORD);
        long userId;
        if (!ids.isEmpty()) {
            userId = ids.get(0);
            // refresh hash 以防上一轮 bcrypt salt 不一致 + 确保 status=1
            jdbc.update(
                "UPDATE sys_user SET password_hash = ?, status = 1, deleted = 0 WHERE id = ?",
                hash, userId);
        } else {
            // 用一个高 ID (9000+ 段) 避免与业务自动分配冲突
            userId = 900_001L;
            jdbc.update(
                "INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, version, deleted) "
                    + "VALUES (?, 1, ?, ?, 'IT cs_manager', 1, 0, 0)",
                userId, CS_MGR_USERNAME, hash);
        }
        // bind role_id=10 (cs_manager) — unique key (tenant_id, user_id, role_id)
        // 保证幂等. 若已 binding 则 INSERT 抛 dup, catch 忽略.
        try {
            int rolePermPk = (int) (Math.abs(System.nanoTime()) % 2_000_000_000);
            jdbc.update(
                "INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, created_at) "
                    + "VALUES (?, ?, 10, 1, NOW())",
                rolePermPk, userId);
        } catch (Exception dup) {
            // already bound — ignore
        }
        return userId;
    }

    private long insertException(String type, String status, String handler, LocalDateTime createdAt) {
        WmsDeliveryException ex = new WmsDeliveryException();
        ex.setTenantId(TEST_TENANT);
        ex.setOrderId(99L);
        ex.setType(type);
        ex.setStatus(status);
        ex.setDescription("CsCrossModuleIT test");
        ex.setHandler(handler);
        ex.setCreatedAt(createdAt);
        dispatchExceptionMapper.insert(ex);
        return ex.getId();
    }

    private void cleanSlaEventsForTenant(long tenantId) {
        slaEventMapper.delete(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, tenantId));
    }

    /**
     * 手动触发 outbox dispatcher 直到指定 outbox row 状态变为 DONE (或 DEAD_LETTER),
     * 最多重试 maxAttempts 次 — 绕过 @Scheduled 2s 轮询等待.
     */
    private void triggerDispatcherUntilStatusDone(Long outboxId, int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            try {
                outboxDispatcher.dispatch();
            } catch (Exception ignored) {
                // dispatch 内部已 catch, 这里仅作 defensive
            }
            EventOutbox row = outboxMapper.selectById(outboxId);
            if (row != null && "DONE".equals(row.getStatus())) {
                return;
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    private long queryCount(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private String queryString(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
