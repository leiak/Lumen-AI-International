package com.lumen.finance.listener;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.event.ClosingAffectedEvent;
import com.lumen.finance.service.MonthlyClosingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 月结被影响事件监听器 (Iteration 4.4, PR2-T2.2).
 *
 * <p>订阅 {@link ClosingAffectedEvent}, 在调账过账后异步调用
 * {@link MonthlyClosingService#recalculateSnapshot(Long, String)} 重算当月快照,
 * 确保月结报表反映最新的调账.
 *
 * <p><b>异步执行:</b> 走 {@code outboxExecutor} (lumen-extension AsyncConfig),
 * 避免阻塞主业务事务.
 *
 * <p><b>TenantContext 处理:</b> 异步 listener 在 {@code outboxExecutor} 线程上
 * 执行, ThreadLocal 不会继承. 必须从事件 {@link ClosingAffectedEvent#getTenantId()}
 * 取值并 set 到 TenantContext, 否则 {@code MybatisPlusConfig} 的
 * {@code TenantLineInnerInterceptor} 会以 tid=0 过滤, 看不到非 tenant=0 行.
 * (4.4 PR2 IT: MonthlyClosingIT.eventListener_recalculatesSnapshotOnAdjustmentPosted
 * 触发了此 bug, 修.)
 *
 * <p><b>失败处理:</b> catch + log — 失败不抛, 因为调账过账已经提交,
 * 月结快照重算可后续手工触发 (via {@code MonthlyClosingService.recalculateSnapshot}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClosingEventListener {

    private final MonthlyClosingService monthlyClosingService;

    @Async("outboxExecutor")
    @EventListener
    public void onClosingAffected(ClosingAffectedEvent event) {
        if (event == null) return;
        try {
            TenantContext.set(event.getTenantId());
            monthlyClosingService.recalculateSnapshot(event.getTenantId(), event.getPeriod());
            log.info("Recalculated snapshot tenant={} period={} adjustmentId={}",
                event.getTenantId(), event.getPeriod(), event.getAdjustmentId());
        } catch (Exception e) {
            log.error("Failed to recalculate snapshot tenant={} period={} adjustmentId={}",
                event.getTenantId(), event.getPeriod(), event.getAdjustmentId(), e);
        } finally {
            TenantContext.clear();
        }
    }
}