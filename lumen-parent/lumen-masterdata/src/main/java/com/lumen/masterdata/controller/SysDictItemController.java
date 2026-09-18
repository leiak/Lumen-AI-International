package com.lumen.masterdata.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.masterdata.entity.SysDictItem;
import com.lumen.masterdata.service.SysDictItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dict-items")
@RequiredArgsConstructor
public class SysDictItemController {
    private final SysDictItemService itemService;

    @GetMapping
    @PreAuthorize("hasAuthority('dict:list')")
    public R<PageResult<SysDictItem>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(itemService.page(pageNum, pageSize, keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('dict:create')")
    @Audit(action = "create", resource = "dict-item")
    public R<SysDictItem> create(@RequestBody SysDictItem entity) {
        return R.ok(itemService.create(entity));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('dict:view')")
    public R<SysDictItem> get(@PathVariable Long id) { return R.ok(itemService.getById(id)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('dict:update')")
    @Audit(action = "update", resource = "dict-item", recordResponse = false)
    public R<SysDictItem> update(@PathVariable Long id, @RequestBody SysDictItem entity) {
        return R.ok(itemService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('dict:delete')")
    @Audit(action = "delete", resource = "dict-item")
    public R<Void> delete(@PathVariable Long id) { itemService.delete(id); return R.ok(null); }
}