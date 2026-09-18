package com.lumen.masterdata.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.masterdata.entity.SysCountry;
import com.lumen.masterdata.service.SysCountryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/countries")
@RequiredArgsConstructor
public class SysCountryController {
    private final SysCountryService countryService;

    @GetMapping
    @PreAuthorize("hasAuthority('country:list')")
    public R<PageResult<SysCountry>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "20") long pageSize,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(countryService.page(pageNum, pageSize, keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('country:create')")
    @Audit(action = "create", resource = "country")
    public R<SysCountry> create(@RequestBody SysCountry entity) {
        return R.ok(countryService.create(entity));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('country:view')")
    public R<SysCountry> get(@PathVariable Long id) { return R.ok(countryService.getById(id)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('country:update')")
    @Audit(action = "update", resource = "country", recordResponse = false)
    public R<SysCountry> update(@PathVariable Long id, @RequestBody SysCountry entity) {
        return R.ok(countryService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('country:delete')")
    @Audit(action = "delete", resource = "country")
    public R<Void> delete(@PathVariable Long id) { countryService.delete(id); return R.ok(null); }
}