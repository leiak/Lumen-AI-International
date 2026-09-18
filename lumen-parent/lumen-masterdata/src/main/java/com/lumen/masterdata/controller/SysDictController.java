package com.lumen.masterdata.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.masterdata.entity.SysDict;
import com.lumen.masterdata.entity.SysDictItem;
import com.lumen.masterdata.service.SysDictService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dicts")
@RequiredArgsConstructor
public class SysDictController {
    private final SysDictService dictService;

    @GetMapping
    @PreAuthorize("hasAuthority('dict:list')")
    public R<PageResult<SysDict>> page(@RequestParam(defaultValue = "1") long pageNum,
                                       @RequestParam(defaultValue = "20") long pageSize,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(dictService.page(pageNum, pageSize, keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('dict:create')")
    @Audit(action = "create", resource = "dict")
    public R<SysDict> create(@RequestBody SysDict entity) {
        return R.ok(dictService.create(entity));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('dict:view')")
    public R<SysDict> get(@PathVariable Long id) { return R.ok(dictService.getById(id)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('dict:update')")
    @Audit(action = "update", resource = "dict", recordResponse = false)
    public R<SysDict> update(@PathVariable Long id, @RequestBody SysDict entity) {
        return R.ok(dictService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('dict:delete')")
    @Audit(action = "delete", resource = "dict")
    public R<Void> delete(@PathVariable Long id) { dictService.delete(id); return R.ok(null); }

    @GetMapping("/{id}/items")
    @PreAuthorize("hasAuthority('dict:view')")
    public R<List<SysDictItem>> items(@PathVariable Long id) { return R.ok(dictService.listItems(id)); }
}