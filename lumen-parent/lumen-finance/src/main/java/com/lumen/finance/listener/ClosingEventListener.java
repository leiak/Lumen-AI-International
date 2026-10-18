package com.lumen.finance.listener;

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
            monthlyClosingService.recalculateSnapshot(event.getTenantId(), event.getPeriod());
            log.info("Recalculated snapshot tenant={} period={} adjustmentId={}",
                event.getTenantId(), event.getPeriod(), event.getAdjustmentId());
        } catch (Exception e) {
            log.error("Failed to recalculate snapshot tenant={} period={} adjustmentId={}",
                event.getTenantId(), event.getPeriod(), event.getAdjustmentId(), e);
        }
    }
}