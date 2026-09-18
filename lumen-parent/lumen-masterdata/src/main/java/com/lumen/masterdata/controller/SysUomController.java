package com.lumen.masterdata.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.masterdata.entity.SysUom;
import com.lumen.masterdata.service.SysUomService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/uoms")
@RequiredArgsConstructor
public class SysUomController {
    private final SysUomService uomService;

    @GetMapping
    @PreAuthorize("hasAuthority('uom:list')")
    public R<PageResult<SysUom>> page(@RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "20") long pageSize,
                                      @RequestParam(required = false) String keyword) {
        return R.ok(uomService.page(pageNum, pageSize, keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('uom:create')")
    @Audit(action = "create", resource = "uom")
    public R<SysUom> create(@RequestBody SysUom entity) {
        return R.ok(uomService.create(entity));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('uom:view')")
    public R<SysUom> get(@PathVariable Long id) { return R.ok(uomService.getById(id)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('uom:update')")
    @Audit(action = "update", resource = "uom", recordResponse = false)
    public R<SysUom> update(@PathVariable Long id, @RequestBody SysUom entity) {
        return R.ok(uomService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('uom:delete')")
    @Audit(action = "delete", resource = "uom")
    public R<Void> delete(@PathVariable Long id) { uomService.delete(id); return R.ok(null); }
}