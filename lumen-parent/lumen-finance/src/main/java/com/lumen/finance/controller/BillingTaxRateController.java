package com.lumen.finance.controller;

import com.lumen.common.api.R;
import com.lumen.finance.entity.BillingTaxRate;
import com.lumen.finance.service.TaxRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 税率 REST 控制器 (Iteration 4.3.2, PR2-T2.6).
 *
 * <p>路径 {@code /api/v1/finance/tax-rates}; 4 个端点:
 * <ul>
 *   <li>{@code GET   /api/v1/finance/tax-rates}              — 按币种过滤查询税率历史</li>
 *   <li>{@code GET   /api/v1/finance/tax-rates/active}       — 取指定币种在某日期有效税率</li>
 *   <li>{@code POST  /api/v1/finance/tax-rates}              — 新增税率记录</li>
 *   <li>{@code POST  /api/v1/finance/tax-rates/{id}/invalidate} — 失效历史税率</li>
 * </ul>
 *
 * <p>权限按动作细粒度:
 * <ul>
 *   <li>{@code finance:taxrate:view} — list + active</li>
 *   <li>{@code finance:taxrate:edit} — create + invalidate</li>
 * </ul>
 *
 * <p>对应 V27 RBAC seed (4.3.2 阶段). 不取 {@code TenantContext} — 税率属于全局共享数据,
 * 多租户共用同一张 {@code t_tax_rate} 表.
 */
@RestController
@RequestMapping("/api/v1/finance/tax-rates")
@RequiredArgsConstructor
public class BillingTaxRateController {

    private final TaxRateService taxRateService;

    /**
     * 按币种过滤查询税率历史 (按 effective_from desc 排序).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('finance:taxrate:view')")
    public R<List<BillingTaxRate>> list(
        @RequestParam(required = false) String currency,
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(defaultValue = "20") Integer size
    ) {
        return R.ok(taxRateService.list(currency, page, size));
    }

    /**
     * 取指定币种在指定日期有效的税率. asOf 缺省取当天.
     */
    @GetMapping("/active")
    @PreAuthorize("hasAuthority('finance:taxrate:view')")
    public R<BigDecimal> getActive(
        @RequestParam String currency,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        LocalDate date = (asOf == null) ? LocalDate.now() : asOf;
        return R.ok(taxRateService.getActiveRate(currency, date));
    }

    /**
     * 新增一条税率记录 (effective_to 可空, 表示长期有效).
     */
    @PostMapping
    @PreAuthorize("hasAuthority('finance:taxrate:edit')")
    public R<BillingTaxRate> create(
        @RequestParam String currency,
        @RequestParam BigDecimal taxRate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveTo
    ) {
        return R.ok(taxRateService.create(currency, taxRate, effectiveFrom, effectiveTo));
    }

    /**
     * 失效一条历史税率 (设置 effective_to).
     */
    @PostMapping("/{id}/invalidate")
    @PreAuthorize("hasAuthority('finance:taxrate:edit')")
    public R<Void> invalidate(
        @PathVariable Long id,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveTo
    ) {
        taxRateService.invalidate(id, effectiveTo);
        return R.ok(null);
    }
}
