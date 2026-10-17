package com.lumen.finance.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.dto.BillingEventLogDtos.View;
import com.lumen.finance.entity.BillingEventLog;
import com.lumen.finance.mapper.BillingEventLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 事件溯源 REST 控制器 (Iteration 4.3.1, PR3-T3.5).
 *
 * <p>路径 {@code /api/v1/finance/event-logs}; 1 个端点 (list, admin only).
 * 权限 {@code finance:eventlog:view} 由 admin 角色独占 (V27 seed).
 *
 * <p>设计目的: 排查计费链路 (幂等失败 / NO_RULE / 表达式超时等) 时的运营仪表盘.
 * 普通 finance_ops / finance_reviewer 不应看到原始事件日志, 避免泄漏业务事件 ID.
 */
@RestController
@RequestMapping("/api/v1/finance/event-logs")
@RequiredArgsConstructor
public class BillingEventLogController {

    private final BillingEventLogMapper eventLogMapper;

    @GetMapping
    @PreAuthorize("hasAuthority('finance:eventlog:view')")
    public R<List<View>> list(@RequestParam(required = false) String businessType,
                              @RequestParam(required = false) String processStatus,
                              @RequestParam(required = false, defaultValue = "1") Integer page,
                              @RequestParam(required = false, defaultValue = "20") Integer size) {
        Long tenantId = TenantContext.require();
        int pn = page < 1 ? 1 : page;
        int ps = size < 1 ? 20 : Math.min(size, 200);

        LambdaQueryWrapper<BillingEventLog> q = new LambdaQueryWrapper<>();
        q.eq(BillingEventLog::getTenantId, tenantId);
        if (businessType != null && !businessType.isBlank()) {
            q.eq(BillingEventLog::getBusinessType, businessType);
        }
        if (processStatus != null && !processStatus.isBlank()) {
            q.eq(BillingEventLog::getProcessStatus, processStatus);
        }
        q.orderByDesc(BillingEventLog::getId);

        Page<BillingEventLog> p = new Page<>(pn, ps);
        Page<BillingEventLog> result = eventLogMapper.selectPage(p, q);
        List<BillingEventLog> records = result.getRecords();
        return R.ok(records.stream().map(BillingEventLogController::toView).toList());
    }

    // ---------- mapper helper ----------

    private static View toView(BillingEventLog e) {
        if (e == null) return null;
        return new View(
            e.getId(),
            e.getTenantId(),
            e.getSourceEventId(),
            e.getBusinessType(),
            e.getBusinessId(),
            e.getProcessStatus(),
            e.getErrorMsg(),
            e.getReceivedAt(),
            e.getProcessedAt()
        );
    }
}