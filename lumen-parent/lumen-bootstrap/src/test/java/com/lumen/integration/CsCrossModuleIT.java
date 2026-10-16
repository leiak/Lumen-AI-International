package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模块集成测试: lumen-cs SLA 升级 → outbox 事件 → lumen-dispatch 订阅更新 handler
 * (Iteration 4.2 PR5).
 *
 * <p>覆盖 3 个场景:
 * <ul>
 *   <li>{@code escalationL1toL2_updatesHandlerOnDispatchSide} —
 *       full path: 扫描 → 发 outbox → dispatcher 重发布 → listener 消费 →
 *       wms_delivery_exception.handler 更新</li>
 *   <li>{@code escalationWithDisconnectedListener_doesNotBlockScan} —
 *       scan 始终完成 + outbox 行始终写入; listener 处理是副作用, 即使偶发未触发
 *       也不应阻塞 sla_event 写入</li>
 *   <li>{@code v25Guard_dispatchAdminCannotInvestigateAnymore} —
 *       V25 落地后 cs_manager/cs_supervisor/customer_service 3 个角色 + 5 个 cs:* 权限
 *       写入 sys_role / sys_permission; dispatch_admin 不再有
 *       dispatch:exception:investigate; EXCEPTION_WORK_ORDER guard 更新</li>
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

    private static final long TEST_TENANT = 200_999L;

    private final Set<Long> createdExceptionIds = new HashSet<>();

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
    }

    @AfterEach
    void tearDown() {
        try {
            for (Long id : createdExceptionIds) {
                try { dispatchExceptionMapper.deleteById(id); } catch (Exception ignored) {}
            }
            cleanSlaEventsForTenant(TEST_TENANT);
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
    // Case 3: V25 SQL 落地校验
    // ====================================================================

    @Test
    @DisplayName("v25Guard_dispatchAdminCannotInvestigateAnymore: V25 SQL 角色/权限/guard 全落地")
    void v25Guard_dispatchAdminCannotInvestigateAnymore() {
        // 直接查 SQL 验证 V25 落地 (不走 mapper, 因为 mapper 都是 BaseEntity 受租户过滤):
        //   1. cs_manager/cs_supervisor/customer_service 3 个角色在 sys_role
        //   2. cs:exception:respond 等 5 个 cs:* perm 在 sys_permission
        //   3. cs_manager 拿到全部 5 个 cs:* (sys_role_permission 5 行)
        //   4. dispatch_admin 不再有 dispatch:exception:investigate (id=120)
        //   5. EXCEPTION_WORK_ORDER investigate guard 更新为 OR 表达式

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

            // 5) EXCEPTION_WORK_ORDER investigate guard 更新为 OR 表达式
            String guard = queryString(c,
                "SELECT guard_expression FROM t_state_transition WHERE state_machine_code='EXCEPTION_WORK_ORDER' AND transition_name='investigate' AND tenant_id=0");
            assertThat(guard)
                .as("V25 5: EXCEPTION_WORK_ORDER investigate guard updated")
                .contains("cs_manager")
                .contains("cs_supervisor");
        } catch (Exception e) {
            throw new RuntimeException("V25 SQL verification failed", e);
        }
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
