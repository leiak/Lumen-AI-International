package com.lumen.wms.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.wms.dto.CreateLocationRequest;
import com.lumen.wms.dto.UpdateLocationRequest;
import com.lumen.wms.entity.WmsLocation;
import com.lumen.wms.service.LocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 库位主数据 REST 控制器.
 *
 * <p>冻结/禁用等状态机操作由 PR3 接入, 当前 PR 仅暴露标准 CRUD + 按仓库下拉.
 */
@RestController
@RequestMapping("/api/v1/wms/locations")
@RequiredArgsConstructor
public class WmsLocationController {

    private final LocationService locationService;

    @GetMapping
    @PreAuthorize("hasAuthority('wms:location:list')")
    public R<PageResult<WmsLocation>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize,
                                           @RequestParam(required = false) Long warehouseId,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String status) {
        return R.ok(locationService.page(pageNum, pageSize, warehouseId, keyword, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:location:list')")
    public R<WmsLocation> getById(@PathVariable Long id) {
        return R.ok(locationService.getById(id));
    }

    @GetMapping("/by-warehouse/{warehouseId}")
    @PreAuthorize("hasAuthority('wms:location:list')")
    public R<List<WmsLocation>> listByWarehouse(@PathVariable Long warehouseId) {
        return R.ok(locationService.listByWarehouse(warehouseId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:location:create')")
    @Audit(action = "create", resource = "wms_location")
    public R<WmsLocation> create(@Valid @RequestBody CreateLocationRequest req) {
        return R.ok(locationService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:location:update')")
    @Audit(action = "update", resource = "wms_location", recordResponse = false)
    public R<WmsLocation> update(@PathVariable Long id, @Valid @RequestBody UpdateLocationRequest req) {
        return R.ok(locationService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:location:delete')")
    @Audit(action = "delete", resource = "wms_location")
    public R<Void> delete(@PathVariable Long id) {
        locationService.delete(id);
        return R.ok(null);
    }
}