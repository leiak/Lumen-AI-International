package com.lumen.wms.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.wms.dto.CreateItemRequest;
import com.lumen.wms.dto.UpdateItemRequest;
import com.lumen.wms.entity.WmsItem;
import com.lumen.wms.service.ItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 货品主数据 REST 控制器.
 */
@RestController
@RequestMapping("/api/v1/wms/items")
@RequiredArgsConstructor
public class WmsItemController {

    private final ItemService itemService;

    @GetMapping
    @PreAuthorize("hasAuthority('wms:item:list')")
    public R<PageResult<WmsItem>> page(@RequestParam(defaultValue = "1") long pageNum,
                                       @RequestParam(defaultValue = "20") long pageSize,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(itemService.page(pageNum, pageSize, keyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:item:list')")
    public R<WmsItem> getById(@PathVariable Long id) {
        return R.ok(itemService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:item:create')")
    @Audit(action = "create", resource = "wms_item")
    public R<WmsItem> create(@Valid @RequestBody CreateItemRequest req) {
        return R.ok(itemService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:item:update')")
    @Audit(action = "update", resource = "wms_item", recordResponse = false)
    public R<WmsItem> update(@PathVariable Long id, @Valid @RequestBody UpdateItemRequest req) {
        return R.ok(itemService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:item:delete')")
    @Audit(action = "delete", resource = "wms_item")
    public R<Void> delete(@PathVariable Long id) {
        itemService.delete(id);
        return R.ok(null);
    }
}