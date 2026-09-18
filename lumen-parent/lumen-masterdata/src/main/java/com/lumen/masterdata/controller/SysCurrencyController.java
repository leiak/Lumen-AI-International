package com.lumen.masterdata.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.masterdata.entity.SysCurrency;
import com.lumen.masterdata.service.SysCurrencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/currencies")
@RequiredArgsConstructor
public class SysCurrencyController {
    private final SysCurrencyService currencyService;

    @GetMapping
    @PreAuthorize("hasAuthority('currency:list')")
    public R<PageResult<SysCurrency>> page(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "20") long pageSize,
                                            @RequestParam(required = false) String keyword) {
        return R.ok(currencyService.page(pageNum, pageSize, keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('currency:create')")
    @Audit(action = "create", resource = "currency")
    public R<SysCurrency> create(@RequestBody SysCurrency entity) {
        return R.ok(currencyService.create(entity));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('currency:view')")
    public R<SysCurrency> get(@PathVariable Long id) { return R.ok(currencyService.getById(id)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('currency:update')")
    @Audit(action = "update", resource = "currency", recordResponse = false)
    public R<SysCurrency> update(@PathVariable Long id, @RequestBody SysCurrency entity) {
        return R.ok(currencyService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('currency:delete')")
    @Audit(action = "delete", resource = "currency")
    public R<Void> delete(@PathVariable Long id) { currencyService.delete(id); return R.ok(null); }
}