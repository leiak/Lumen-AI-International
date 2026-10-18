package com.lumen.finance.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 月结被影响事件 (Iteration 4.4, PR2-T2.2).
 *
 * <p>由 {@link com.lumen.finance.service.impl.AdjustmentServiceImpl#markPosted}
 * 在调账过账 (L2_APPROVED → POSTED) 后发布, 通知
 * {@link com.lumen.finance.listener.ClosingEventListener} 重算当月快照
 * ({@code MonthlyClosingService.recalculateSnapshot}).
 *
 * <p>异步执行 (Spring {@code @Async}), 不影响主业务事务.
 */
@Getter
public class ClosingAffectedEvent extends ApplicationEvent {

    private final Long tenantId;
    private final String period;
    private final Long adjustmentId;

    public ClosingAffectedEvent(Object source, Long tenantId, String period, Long adjustmentId) {
        super(source);
        this.tenantId = tenantId;
        this.period = period;
        this.adjustmentId = adjustmentId;
    }
}