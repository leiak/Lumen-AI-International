package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.extension.outbox.EventOutbox;
import com.lumen.extension.outbox.EventOutboxMapper;
import com.lumen.extension.outbox.OutboxDispatcher;
import com.lumen.extension.state.StateMachineException;
import com.lumen.masterdata.entity.SysCountry;
import com.lumen.masterdata.mapper.SysCountryMapper;
import com.lumen.masterdata.service.SysCountryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 集成测试：SysCountry.changeState 走表驱动状态机 + EventBus。
 *
 * <ol>
 *   <li>{@code validTransition_persists_andEmitsEvent} —
 *       DRAFT → ACTIVE 走通，status 写入 DB，outbox 表新增一行
 *       {@code country.state_changed} 事件（status=PENDING）。</li>
 *   <li>{@code invalidTransition_throws_andNoEvent} —
 *       DRAFT → FROZEN 不在 sys_country 表里，抛 StateMachineException，
 *       DB 不变，outbox 不写入。</li>
 *   <li>{@code eventDeliveredViaOutboxDispatcher} —
 *       changeState 后 {@code OutboxDispatcher.dispatch()} 把事件推到
 *       {@link CountryStateChangeListener}（top-level {@code @Component} + @Import）。</li>
 * </ol>
 *
 * <p>{@link CountryStateChangeListener} 独立为顶层类（与
 * {@link OutboxTestListener} / {@link OutboxCountingListener} 同源考量），
 * 避开 nested @Component + @Import 在 @SpringBootTest 里 @EventListener
 * 偶发漏注册。
 */
@Import(CountryStateChangeListener.class)
class CountryStateTransitionIT extends IntegrationBase {

    @Autowired SysCountryService countryService;
    @Autowired SysCountryMapper countryMapper;
    @Autowired EventOutboxMapper outboxMapper;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired CountryStateChangeListener listener;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void validTransition_persists_andEmitsEvent() {
        SysCountry c = createCountry("DRAFT");
        listener.clear();
        // 不设 TenantContext → 默认 tenant=0，与 V9 sys_country 种子 (tenant_id=0) 对齐。
        try {
            countryService.changeState(c.getId(), "ACTIVE", 1L);

            SysCountry reloaded = countryMapper.selectById(c.getId());
            assertThat(reloaded.getStatus()).isEqualTo("ACTIVE");

            // outbox 应有 1 行 country.state_changed 事件（PENDING）。
            // 注意：SysCountry 唯一索引是 (tenant_id, code)，不同次跑测试 code 必须不同。
            List<EventOutbox> rows = outboxMapper.selectList(
                    new LambdaQueryWrapper<EventOutbox>().eq(EventOutbox::getAggregateId, String.valueOf(c.getId()))
            );
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getStatus()).isEqualTo("PENDING");
            assertThat(rows.get(0).getEventType()).isEqualTo("country.state_changed");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void invalidTransition_throws_andNoEvent() {
        SysCountry c = createCountry("DRAFT");
        try {
            assertThatThrownBy(() -> countryService.changeState(c.getId(), "FROZEN", 1L))
                    .isInstanceOf(StateMachineException.class);

            SysCountry reloaded = countryMapper.selectById(c.getId());
            assertThat(reloaded.getStatus()).isEqualTo("DRAFT");  // 未变

            long count = outboxMapper.selectCount(
                    new LambdaQueryWrapper<EventOutbox>().eq(EventOutbox::getAggregateId, String.valueOf(c.getId()))
            );
            assertThat(count).isZero();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void eventDeliveredViaOutboxDispatcher() {
        SysCountry c = createCountry("DRAFT");
        listener.clear();
        try {
            countryService.changeState(c.getId(), "ACTIVE", 1L);
            // 重置 next_retry_at 让 dispatcher 立刻能锁到这一行（outbox 默认 next_retry_at = now，
            // 但在并发场景下 @Scheduled 轮询可能还没赶上；这里手动 dispatch 一次更稳）。
            EventOutbox row = outboxMapper.selectList(
                    new LambdaQueryWrapper<EventOutbox>().eq(EventOutbox::getAggregateId, String.valueOf(c.getId()))
            ).get(0);
            row.setNextRetryAt(java.time.LocalDateTime.now().minusSeconds(1));
            outboxMapper.updateById(row);

            // 强制 dispatch 一次，绕开 @Scheduled 2s 轮询。
            // listener.clear() 在 publish 路径上的 DefaultEventBus.publish 同步事件已清掉。
            listener.clear();
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(status -> dispatcher.dispatch());

            // listener 是顶层 @Component 被 Spring 全局共享，其他 IT 也可能写入 outbox
            // 并经 dispatcher 路径派发到本 listener —— 我们只过滤出当前 country 的事件断言。
            var mineEvents = listener.getReceived().stream()
                    .filter(e -> e.getCountryId().equals(c.getId()))
                    .toList();
            assertThat(mineEvents)
                    .as("dispatcher path must deliver at least 1 event for this country")
                    .isNotEmpty();
            assertThat(mineEvents)
                    .anySatisfy(e -> {
                        assertThat(e.getOldState()).isEqualTo("DRAFT");
                        assertThat(e.getNewState()).isEqualTo("ACTIVE");
                    });
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /**
     * 直接 insert 一条 SysCountry。SysCountry 字段名是 {@code code}（不是
     * {@code countryCode}），主键策略 {@code IdType.ASSIGN_ID}。
     */
    private SysCountry createCountry(String status) {
        SysCountry c = new SysCountry();
        // sys_country.code 是 VARCHAR(8) 且唯一索引 (tenant_id, code)。
        // 用 nanoTime 末 6 位 + 静态计数器保证唯一性 + 长度限制。
        c.setCode("T" + String.format("%06d", (System.nanoTime() % 1_000_000) + counter.getAndIncrement()));
        c.setNameCn("测试国");
        c.setNameEn("Test Country");
        c.setStatus(status);
        // 设 TenantContext=0L 让 BaseEntity tenant_id 自动填充 0，与 V9 sys_country 种子
        // (tenant_id=0) 对齐 —— StateMachineRegistry 查询 t_state_transition 时 MybatisPlus
        // tenant 拦截器会加 AND tenant_id = ?，需要插入/查询的 tenant 一致才能匹配。
        TenantContext.set(0L);
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(s -> countryMapper.insert(c));
            return c;
        } finally {
            TenantContext.clear();
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong counter = new java.util.concurrent.atomic.AtomicLong();
}
