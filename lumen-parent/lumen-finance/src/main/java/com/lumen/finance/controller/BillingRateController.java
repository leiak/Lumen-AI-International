package com.lumen.finance.controller;

import com.lumen.common.api.R;
import com.lumen.common.security.SecurityUtil;
import com.lumen.finance.entity.BillingExchangeRate;
import com.lumen.finance.rates.RateProvider;
import com.lumen.finance.rates.RateSyncService;
import com.lumen.finance.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 汇率 REST 控制器 (Iteration 4.3.2, PR2-T2.5).
 *
 * <p>路径 {@code /api/v1/finance/rates}; 3 个端点:
 * <ul>
 *   <li>{@code GET   /api/v1/finance/rates}        — 分页/过滤查询汇率历史</li>
 *   <li>{@code POST  /api/v1/finance/rates/refresh} — 即时触发远端拉取 (USD→CNY)</li>
 *   <li>{@code POST  /api/v1/finance/rates/manual}  — 手动录入 (admin/finance 操作员)</li>
 * </ul>
 *
 * <p>权限按动作细粒度:
 * <ul>
 *   <li>{@code finance:rate:view} — list + refresh</li>
 *   <li>{@code finance:rate:edit} — manual</li>
 * </ul>
 *
 * <p>对应 V27 RBAC seed (4.3.2 阶段). 不取 {@code TenantContext} — 汇率属于全局共享数据,
 * 多租户共用同一张 {@code t_exchange_rate} 表, 不做租户隔离. operatorId 仅用于留痕
 * (后续可写入 audit log, 当前 saveManual 暂未持久化 operatorId, 见 4.3.2-PR2-T2.4).
 */
@RestController
@RequestMapping("/api/v1/finance/rates")
@RequiredArgsConstructor
public class BillingRateController {

    private final ExchangeRateService exchangeRateService;
    private final RateSyncService rateSyncService;

    /**
     * 分页查询汇率 (from/to/fromDate/toDate 任意组合可空). 按 rateDate desc 排序.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('finance:rate:view')")
    public R<List<BillingExchangeRate>> list(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(defaultValue = "20") Integer size
    ) {
        return R.ok(exchangeRateService.list(from, to, fromDate, toDate, page, size));
    }

    /**
     * 手动触发远端拉取 (USD→CNY). 立即同步并返回最新报价 (rate + rateDate + source).
     * 远端失败时抛 {@code RATE_SYNC_FAILED}.
     */
    @PostMapping("/refresh")
    @PreAuthorize("hasAuthority('finance:rate:view')")
    public R<RateProvider.RateQuote> refresh() {
        return R.ok(rateSyncService.manualSync());
    }

    /**
     * 手动录入汇率 (source=MANUAL). 用于补充远端拉取失败的特殊日期, 或覆盖合同约定的汇率.
     */
    @PostMapping("/manual")
    @PreAuthorize("hasAuthority('finance:rate:edit')")
    public R<Void> manual(
        @RequestParam String from,
        @RequestParam String to,
        @RequestParam BigDecimal rate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rateDate
    ) {
        Long operatorId = SecurityUtil.getCurrentUserId();
        exchangeRateService.saveManual(from, to, rate, rateDate, operatorId);
        return R.ok(null);
    }
}