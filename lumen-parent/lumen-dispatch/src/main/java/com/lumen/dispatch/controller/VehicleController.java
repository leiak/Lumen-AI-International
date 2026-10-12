package com.lumen.dispatch.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest;
import com.lumen.dispatch.dto.VehicleDtos.QueryVehicleRequest;
import com.lumen.dispatch.dto.VehicleDtos.UpdateVehicleRequest;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.service.VehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 车辆主数据 REST 控制器.
 *
 * <p>路径对齐 WMS WarehouseController；权限细粒度分到 {@code dispatch:vehicle:*}.
 *
 * <p>冻结 / 解冻由 {@code dispatch:vehicle:freeze} 守门；{@code freeze} 触
 * 发 V20 VEHICLE 状态机的 'out' transition (任意非终端 → OUT).
 */
@RestController
@RequestMapping("/api/v1/wms/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    @GetMapping
    @PreAuthorize("hasAuthority('dispatch:vehicle:list')")
    public R<PageResult<WmsVehicle>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String plateNo) {
        QueryVehicleRequest q = new QueryVehicleRequest();
        q.setStatus(status);
        q.setType(type);
        q.setPlateNo(plateNo);
        return R.ok(vehicleService.page(current, Math.min(size, 200L), q));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch:vehicle:list')")
    public R<WmsVehicle> get(@PathVariable Long id) {
        return R.ok(vehicleService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('dispatch:vehicle:create')")
    @Audit(action = "create", resource = "dispatch_vehicle")
    public R<WmsVehicle> create(@Valid @RequestBody CreateVehicleRequest req) {
        return R.ok(vehicleService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch:vehicle:update')")
    @Audit(action = "update", resource = "dispatch_vehicle", recordResponse = false)
    public R<WmsVehicle> update(@PathVariable Long id, @Valid @RequestBody UpdateVehicleRequest req) {
        return R.ok(vehicleService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch:vehicle:update')")
    @Audit(action = "delete", resource = "dispatch_vehicle")
    public R<Void> delete(@PathVariable Long id) {
        vehicleService.delete(id);
        return R.ok(null);
    }

    @PostMapping("/{id}/freeze")
    @PreAuthorize("hasAuthority('dispatch:vehicle:freeze')")
    @Audit(action = "freeze", resource = "dispatch_vehicle")
    public R<WmsVehicle> freeze(@PathVariable Long id) {
        return R.ok(vehicleService.freeze(id));
    }

    @PostMapping("/{id}/unfreeze")
    @PreAuthorize("hasAuthority('dispatch:vehicle:freeze')")
    @Audit(action = "unfreeze", resource = "dispatch_vehicle")
    public R<WmsVehicle> unfreeze(@PathVariable Long id) {
        return R.ok(vehicleService.unfreeze(id));
    }
}