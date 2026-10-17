package com.lumen.finance.controller;

import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.dto.ClosingDtos.CloseRequest;
import com.lumen.finance.dto.ClosingDtos.ReopenRequest;
import com.lumen.finance.dto.ClosingDtos.StartRequest;
import com.lumen.finance.entity.BillingMonthlyClosing;
import com.lumen.finance.service.MonthlyClosingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 月结 REST 控制器 (Iteration 4.4, Task 1.7).
 *
 * <p>路径 {@code /api/v1/finance/closing}; 5 个端点 (list + get + start + close + reopen).
 * 权限: {@code finance:closing:manage} — 全部端点共用同一权限 (月结是高敏操作,
 * 财务经理及以上).
 *
 * <p>状态机: {@code OPEN → STARTED → CLOSED}; {@code CLOSED → OPEN} 仅可通过
 * reopen (要求 reason ≥ 10 字符). 全部校验在 service 层完成,
 * 违规抛 {@code FinanceException} → 400.
 */
@RestController
@RequestMapping("/api/v1/finance/closing")
@RequiredArgsConstructor
public class BillingClosingController {

    private final MonthlyClosingService monthlyClosingService;

    /**
     * 分页列出指定租户的月结记录 (按 period desc 排序).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('finance:closing:manage')")
    public R<List<BillingMonthlyClosing>> list(
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(defaultValue = "20") Integer size
    ) {
        Long tenantId = TenantContext.require();
        return R.ok(monthlyClosingService.list(tenantId, page, size));
    }

    /**
     * 按 period (YYYY-MM) 取月结快照. 不存在 → 自动 {@code getOrCreate}
     * 以 {@code OPEN} 状态返回 (service 层策略).
     */
    @GetMapping("/{period}")
    @PreAuthorize("hasAuthority('finance:closing:manage')")
    public R<BillingMonthlyClosing> get(@PathVariable String period) {
        Long tenantId = TenantContext.require();
        return R.ok(monthlyClosingService.getOrCreate(tenantId, period));
    }

    /**
     * 开启月结: {@code OPEN → STARTED}. 请求体见 {@link StartRequest}.
     */
    @PostMapping("/start")
    @PreAuthorize("hasAuthority('finance:closing:manage')")
    public R<BillingMonthlyClosing> start(@Valid @RequestBody StartRequest req) {
        Long tenantId = TenantContext.require();
        return R.ok(monthlyClosingService.start(tenantId, req.getPeriod()));
    }

    /**
     * 关闭月结: {@code STARTED → CLOSED}. 期间内的应收 / 应付单据必须全部
     * {@code L2_APPROVED} (由 service 层硬校验).
     */
    @PostMapping("/close")
    @PreAuthorize("hasAuthority('finance:closing:manage')")
    public R<BillingMonthlyClosing> close(@Valid @RequestBody CloseRequest req) {
        Long tenantId = TenantContext.require();
        return R.ok(monthlyClosingService.close(tenantId, req.getPeriod()));
    }

    /**
     * 重开已关闭月份: {@code CLOSED → OPEN}, reason 必填 (≥ 10 字符).
     * 用于 L2 终审后发现的错误纠正场景.
     */
    @PostMapping("/reopen")
    @PreAuthorize("hasAuthority('finance:closing:manage')")
    public R<BillingMonthlyClosing> reopen(@Valid @RequestBody ReopenRequest req) {
        Long tenantId = TenantContext.require();
        return R.ok(monthlyClosingService.reopen(tenantId, req.getPeriod(), req.getReason()));
    }
}