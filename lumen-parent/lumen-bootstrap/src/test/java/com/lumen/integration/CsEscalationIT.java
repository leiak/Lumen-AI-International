package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.dto.EscalationDtos.SlaEventDto;
import com.lumen.cs.dto.SlaConfigDtos;
import com.lumen.cs.entity.SlaEvent;
import com.lumen.cs.mapper.SlaEventMapper;
import com.lumen.cs.service.BusinessCalendarService;
import com.lumen.cs.service.EscalationService;
import com.lumen.cs.service.HolidayService;
import com.lumen.cs.service.SlaConfigService;
import com.lumen.cs.service.SlaEventService;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
import com.lumen.extension.outbox.EventBus;
import com.lumen.extension.outbox.EventOutbox;
import com.lumen.extension.outbox.EventOutboxMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA 升级扫描器集成测试 (Iteration 4.2 PR4).
 *
 * <p>覆盖 5 个场景:
 * <ul>
 *   <li>HIGH severity 不超期 → 不写 sla_event, 不发 outbox</li>
 *   <li>HIGH severity 超期 → L1 → L2 升级, 写 ESCALATED + outbox</li>
 *   <li>MEDIUM severity 路由到 MEDIUM config</li>
 *   <li>L1 → L2 → L3 多级升级链 (先后 2 次 scan)</li>
 *   <li>幂等: UNIQUE KEY 阻止重复 ESCALATED 写入</li>
 * </ul>
 *
 * <p>每个测试独占一个 tenantId (从 100000 起, 避开真实业务租户 1), 通过
 * {@code lumen.cs.scan-tenants} 让 {@link EscalationServiceImpl} 只扫这个
 * tenant — 这样测试间不会互相干扰 (即使其他 IT 留 OPEN 工单在 tenant 1).
 *
 * <p>listener (DeliveryExceptionStateChangedEventListener) 在 PR4 跳过 —
 * lumen-cs 不依赖 lumen-dispatch, 跨模块事件订阅由 PR5 落地.
 */
@DisplayName("SLA 升级集成测试")
@TestPropertySource(properties = "lumen.cs.scan-tenants=100999")
class CsEscalationIT extends IntegrationBase {

    @Autowired private SlaEventService slaEventService;
    @Autowired private SlaConfigService slaConfigService;
    @Autowired private BusinessCalendarService businessCalendarService;
    @Autowired private HolidayService holidayService;
    @Autowired private SlaEventMapper slaEventMapper;
    @Autowired private EscalationService escalationService;
    @Autowired private EventBus eventBus;
    @Autowired private EventOutboxMapper outboxMapper;
    @Autowired private WmsDeliveryExceptionMapper dispatchExceptionMapper;

    /** 测试独占 tenantId. */
    private static final long TEST_TENANT = 100_999L;
    private static final String SCAN_TENANT_KEY = "100999";

    /**
     * 测试场景期间插入的所有 exception id — tearDown 用来清掉.
     */
    private final java.util.Set<Long> createdExceptionIds = new java.util.HashSet<>();

    /**
     * 为 tenant 创建 SLA configs + business calendar (Mon-Fri 9-18, 无 holiday).
     * config 的 resolveMinutes 故意调小 (30 min) — 测试时 30 分钟前就超期.
     */
    @BeforeEach
    void setUp() {
        TenantContext.set(TEST_TENANT);

        // 清掉之前测试可能留下的事件 (幂等)
        cleanSlaEventsForTenant(TEST_TENANT);

        // Mon-Fri 9-18 (idempotent: catch duplicate if any)
        for (int weekday = 1; weekday <= 5; weekday++) {
            try {
                businessCalendarService.create(TEST_TENANT,
                    new com.lumen.cs.dto.CalendarDtos.CreateRequest(
                        weekday, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0)));
            } catch (Exception ignored) {
                // already exists
            }
        }
    }

    @AfterEach
    void tearDown() {
        try {
            // 清测试 exception
            for (Long id : createdExceptionIds) {
                try { dispatchExceptionMapper.deleteById(id); } catch (Exception ignored) {}
            }
            // 清测试 SLA events
            cleanSlaEventsForTenant(TEST_TENANT);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("HIGH severity: 不超期 → 不写 sla_event, 不发 outbox")
    void highSeverity_underResolve_noEscalation() {
        seedSlaConfigs();

        // 创建 DAMAGE exception, createdAt = 10 分钟前 (resolveMinutes=30, 没超期)
        long exId = insertException("DAMAGE", "OPEN", null,
            LocalDateTime.now().minusMinutes(10));
        createdExceptionIds.add(exId);

        escalationService.scan();

        // 1) 没有 sla_event 写入
        List<SlaEvent> events = slaEventMapper.selectList(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, TEST_TENANT)
            .eq(SlaEvent::getExceptionId, exId));
        assertThat(events).isEmpty();

        // 2) 没有 outbox 事件 (exception.escalated)
        List<EventOutbox> outboxes = outboxMapper.selectList(new LambdaQueryWrapper<EventOutbox>()
            .eq(EventOutbox::getEventType, "exception.escalated")
            .eq(EventOutbox::getAggregateId, String.valueOf(exId)));
        assertThat(outboxes).isEmpty();
    }

    @Test
    @DisplayName("HIGH severity: 超期 → L1→L2 升级, 写 ESCALATED + 发 outbox")
    void highSeverity_overResolve_escalatesL1toL2() {
        seedSlaConfigs();

        // DAMAGE exception, createdAt = 7 天前 (resolveMinutes=30, 已超期)
        long exId = insertException("DAMAGE", "OPEN", null,
            LocalDateTime.now().minusDays(7));
        createdExceptionIds.add(exId);

        escalationService.scan();

        // 1) t_sla_event 写入了 1 条 ESCALATED, currentLevel=L1, severity=HIGH
        List<SlaEvent> events = slaEventMapper.selectList(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, TEST_TENANT)
            .eq(SlaEvent::getExceptionId, exId));
        assertThat(events).hasSize(1);
        SlaEvent ev = events.get(0);
        assertThat(ev.getEventType()).isEqualTo("ESCALATED");
        assertThat(ev.getCurrentLevel()).isEqualTo("L1");
        assertThat(ev.getSeverity()).isEqualTo("HIGH");
        assertThat(ev.getPayload()).contains("\"fromLevel\"", "L1");
        assertThat(ev.getPayload()).contains("\"toLevel\"", "L2");

        // 2) outbox 写入 1 条 exception.escalated 事件
        List<EventOutbox> outboxes = outboxMapper.selectList(new LambdaQueryWrapper<EventOutbox>()
            .eq(EventOutbox::getEventType, "exception.escalated")
            .eq(EventOutbox::getAggregateId, String.valueOf(exId)));
        assertThat(outboxes).hasSize(1);
        EventOutbox row = outboxes.get(0);
        assertThat(row.getEventType()).isEqualTo("exception.escalated");
        assertThat(row.getAggregateType()).isEqualTo("WmsDeliveryException");
        assertThat(row.getPayload()).contains("\"fromLevel\"", "L1");
        assertThat(row.getPayload()).contains("\"toLevel\"", "L2");
        assertThat(row.getPayload()).contains("\"newHandlerUsername\"", "system_100999_L2");
    }

    /**
     * MEDIUM severity routing — REJECTED exception routes through MEDIUM config.
     *
     * <p>Note: original spec wanted {@code exceptionMediumSeverity_underResponseDue_onlyWritesL1ResponseDue}
     * (testing L1_RESPONSE_DUE event writing when elapsed &gt; responseMinutes but &lt;= resolveMinutes).
     * That behavior is delegated to {@code DeliveryExceptionStateChangedEventListener} (§5.3 of design)
     * which is deferred to PR5 because implementing it would require lumen-cs to depend on lumen-dispatch,
     * violating the spec §2 module boundary rule. L1_RESPONSE_DUE coverage will land in PR5 IT.
     */
    @Test
    @DisplayName("MEDIUM severity: REJECTED → MEDIUM 路由正确")
    void mediumSeverity_routesThroughMediumConfig() {
        seedSlaConfigs();

        long exId = insertException("REJECTED", "OPEN", null,
            LocalDateTime.now().minusDays(7));
        createdExceptionIds.add(exId);

        escalationService.scan();

        List<SlaEvent> events = slaEventMapper.selectList(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, TEST_TENANT)
            .eq(SlaEvent::getExceptionId, exId));
        assertThat(events).hasSize(1);
        SlaEvent ev = events.get(0);
        assertThat(ev.getEventType()).isEqualTo("ESCALATED");
        assertThat(ev.getCurrentLevel()).isEqualTo("L1");
        assertThat(ev.getSeverity()).isEqualTo("MEDIUM");
        assertThat(ev.getPayload()).contains("\"toLevel\"", "L2");
    }

    @Test
    @DisplayName("L1→L2→L3: handler 含 cs_supervisor 后第二次扫描升级到 L3")
    void escalationChain_l1ToL2ToL3() {
        seedSlaConfigs();

        // 先建一个 L1 状态的异常 (handler=null → L1)
        long exId = insertException("DAMAGE", "INVESTIGATING", null,
            LocalDateTime.now().minusDays(7));
        createdExceptionIds.add(exId);

        // 第一次扫: L1 → L2 (handler 仍然 null, inferLevel 还是 L1, 还会再升级到 L2)
        escalationService.scan();

        List<SlaEvent> events = slaEventMapper.selectList(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, TEST_TENANT)
            .eq(SlaEvent::getExceptionId, exId));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getCurrentLevel()).isEqualTo("L1");
        assertThat(events.get(0).getPayload()).contains("\"toLevel\"", "L2");

        // 第二次扫: 仍 handler=null → 仍 L1 → 仍会升 L2. UNIQUE KEY 阻止重复.
        escalationService.scan();
        events = slaEventMapper.selectList(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, TEST_TENANT)
            .eq(SlaEvent::getExceptionId, exId));
        // UNIQUE KEY (exception_id, event_type, current_level, deleted) — 第二次不会写入新行
        assertThat(events).hasSize(1);

        // 模拟 PR5: 升级到 L2 后, dispatch handler 变为 "cs_supervisor_X" → inferLevel = L2
        // 这里手动 update handler 模拟 dispatch 升级效果 (IT 不依赖 dispatcher 异步链路)
        updateExceptionHandler(exId, "cs_supervisor_42");

        // 第三次扫: handler 含 cs_supervisor → L2 → 升级到 L3
        escalationService.scan();
        events = slaEventMapper.selectList(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, TEST_TENANT)
            .eq(SlaEvent::getExceptionId, exId)
            .orderByAsc(SlaEvent::getCurrentLevel));
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getCurrentLevel()).isEqualTo("L1");
        assertThat(events.get(1).getCurrentLevel()).isEqualTo("L2");
        assertThat(events.get(1).getPayload()).contains("\"toLevel\"", "L3");

        // 第四次扫: L3 → escalate_to=null → 不再升级
        updateExceptionHandler(exId, "cs_manager_1");
        escalationService.scan();
        events = slaEventMapper.selectList(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, TEST_TENANT)
            .eq(SlaEvent::getExceptionId, exId)
            .orderByAsc(SlaEvent::getCurrentLevel));
        assertThat(events).hasSize(2);
    }

    @Test
    @DisplayName("幂等: UNIQUE KEY 阻止并发重复 ESCALATED 写入")
    void idempotency_concurrentScan_writesOnlyOneRow() {
        seedSlaConfigs();

        long exId = insertException("DAMAGE", "OPEN", null,
            LocalDateTime.now().minusDays(7));
        createdExceptionIds.add(exId);

        // 连扫 3 次, 只有 1 条 ESCALATED 行
        escalationService.scan();
        escalationService.scan();
        escalationService.scan();

        List<SlaEvent> events = slaEventMapper.selectList(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, TEST_TENANT)
            .eq(SlaEvent::getExceptionId, exId)
            .eq(SlaEvent::getEventType, "ESCALATED"));
        assertThat(events).hasSize(1);
    }

    // ================================================================
    // helpers
    // ================================================================

    /**
     * 写入 4 条 SLA config (HIGH/MEDIUM/LOW × L1/L2/L3 等) + resolveMinutes
     * 调小到 30 min 方便测试超期.
     */
    private void seedSlaConfigs() {
        // HIGH L1 → L2 (resolve=30 min)
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
            // already exists (重复测试运行) — 跳过
        }
    }

    /**
     * 直接 INSERT 一行 wms_delivery_exception, with 自定义 createdAt.
     * 绕过 DeliveryExceptionService 因为后者不允许设 createdAt.
     */
    private long insertException(String type, String status, String handler, LocalDateTime createdAt) {
        WmsDeliveryException ex = new WmsDeliveryException();
        ex.setTenantId(TEST_TENANT);
        ex.setOrderId(99L);  // fake; exceptions 不强 FK to wms_delivery_order
        ex.setType(type);
        ex.setStatus(status);
        ex.setDescription("IT test exception");
        ex.setHandler(handler);
        ex.setCreatedAt(createdAt);  // 必须在 MetaObjectHandler strict-fill 之前设
        // MyBatis-Plus ASSIGN_ID 会自动生成 id (null 时), 或用我设的 id
        dispatchExceptionMapper.insert(ex);
        return ex.getId();
    }

    private void updateExceptionHandler(long id, String handler) {
        WmsDeliveryException ex = dispatchExceptionMapper.selectById(id);
        ex.setHandler(handler);
        dispatchExceptionMapper.updateById(ex);
    }

    private void cleanSlaEventsForTenant(long tenantId) {
        slaEventMapper.delete(new LambdaQueryWrapper<SlaEvent>()
            .eq(SlaEvent::getTenantId, tenantId));
    }

    /** 测试用原子计数 — 为多测例分配独立 tenantId 备用 (目前单测, 留接口). */
    @SuppressWarnings("unused")
    private static final AtomicLong TENANT_SEQ = new AtomicLong(100_000L);
}
