package com.lumen.wms.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.wms.dto.CreateWarehouseRequest;
import com.lumen.wms.dto.UpdateWarehouseRequest;
import com.lumen.wms.entity.WmsWarehouse;
import com.lumen.wms.service.WarehouseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 仓库主数据 REST 控制器.
 *
 * <p>冻结/禁用等状态机操作由 PR3 接入, 当前 PR 仅暴露标准 CRUD.
 */
@RestController
@RequestMapping("/api/v1/wms/warehouses")
@RequiredArgsConstructor
public class WmsWarehouseController {

    private final WarehouseService warehouseService;

    @GetMapping
    @PreAuthorize("hasAuthority('wms:warehouse:list')")
    public R<PageResult<WmsWarehouse>> page(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "20") long pageSize,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String status) {
        return R.ok(warehouseService.page(pageNum, pageSize, keyword, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:warehouse:list')")
    public R<WmsWarehouse> getById(@PathVariable Long id) {
        return R.ok(warehouseService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:warehouse:create')")
    @Audit(action = "create", resource = "wms_warehouse")
    public R<WmsWarehouse> create(@Valid @RequestBody CreateWarehouseRequest req) {
        return R.ok(warehouseService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:warehouse:update')")
    @Audit(action = "update", resource = "wms_warehouse", recordResponse = false)
    public R<WmsWarehouse> update(@PathVariable Long id, @Valid @RequestBody UpdateWarehouseRequest req) {
        return R.ok(warehouseService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:warehouse:delete')")
    @Audit(action = "delete", resource = "wms_warehouse")
    public R<Void> delete(@PathVariable Long id) {
        warehouseService.delete(id);
        return R.ok(null);
    }
}