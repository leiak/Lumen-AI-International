package com.lumen.dispatch.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.dispatch.dto.DeliveryOrderDtos.ArriveRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.AssignRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.CancelRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.CreateDeliveryOrderRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.DepartRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.LoadRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.QueryDeliveryOrderRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.RaiseExceptionRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.SignRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.UpdateDeliveryOrderRequest;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsDeliveryStop;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.dispatch.service.DispatchEngine;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 配送订单 REST 控制器 (Iteration 4.1 PR4).
 *
 * <p>路径对齐 spec §10 PR4 — 前缀 {@code /api/v1/dispatch/orders};权限按动作细粒度.
 * 状态机迁移端点由 {@code dispatch:order:*} 系列守门.
 */
@RestController
@RequestMapping("/api/v1/dispatch/orders")
@RequiredArgsConstructor
public class DeliveryOrderController {

    private final DeliveryOrderService deliveryOrderService;
    private final DispatchEngine dispatchEngine;

    // ================================================================
    // CRUD
    // ================================================================

    @GetMapping
    @PreAuthorize("hasAuthority('dispatch:order:list')")
    public R<PageResult<WmsDeliveryOrder>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) Long driverId,
            @RequestParam(required = false) Long warehouseId) {
        QueryDeliveryOrderRequest q = new QueryDeliveryOrderRequest();
        q.setStatus(status);
        q.setVehicleId(vehicleId);
        q.setDriverId(driverId);
        q.setWarehouseId(warehouseId);
        return R.ok(deliveryOrderService.page(current, Math.min(size, 200L), q));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch:order:list')")
    public R<WmsDeliveryOrder> get(@PathVariable Long id) {
        return R.ok(deliveryOrderService.get(id));
    }

    @GetMapping("/{id}/stops")
    @PreAuthorize("hasAuthority('dispatch:order:list')")
    public R<List<WmsDeliveryStop>> listStops(@PathVariable Long id) {
        return R.ok(deliveryOrderService.listStops(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('dispatch:order:create')")
    @Audit(action = "create", resource = "dispatch_order")
    public R<WmsDeliveryOrder> create(@Valid @RequestBody CreateDeliveryOrderRequest req) {
        return R.ok(deliveryOrderService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch:order:update')")
    @Audit(action = "update", resource = "dispatch_order", recordResponse = false)
    public R<WmsDeliveryOrder> update(@PathVariable Long id,
                                      @Valid @RequestBody UpdateDeliveryOrderRequest req) {
        return R.ok(deliveryOrderService.update(id, req));
    }

    // ================================================================
    // State machine transitions (spec §7.1)
    // ================================================================

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('dispatch:order:assign')")
    @Audit(action = "assign", resource = "dispatch_order")
    public R<WmsDeliveryOrder> assign(@PathVariable Long id, @Valid @RequestBody AssignRequest req) {
        return R.ok(deliveryOrderService.assign(id, req));
    }

    @PostMapping("/{id}/load")
    @PreAuthorize("hasAuthority('dispatch:order:load')")
    @Audit(action = "load", resource = "dispatch_order")
    public R<WmsDeliveryOrder> load(@PathVariable Long id, @Valid @RequestBody LoadRequest req) {
        return R.ok(deliveryOrderService.load(id, req));
    }

    @PostMapping("/{id}/depart")
    @PreAuthorize("hasAuthority('dispatch:order:depart')")
    @Audit(action = "depart", resource = "dispatch_order")
    public R<WmsDeliveryOrder> depart(@PathVariable Long id, @Valid @RequestBody DepartRequest req) {
        return R.ok(deliveryOrderService.depart(id, req));
    }

    @PostMapping("/{id}/arrive")
    @PreAuthorize("hasAuthority('dispatch:order:arrive')")
    @Audit(action = "arrive", resource = "dispatch_order")
    public R<WmsDeliveryOrder> arrive(@PathVariable Long id, @Valid @RequestBody ArriveRequest req) {
        return R.ok(deliveryOrderService.arrive(id, req));
    }

    @PostMapping("/{id}/sign")
    @PreAuthorize("hasAuthority('dispatch:order:sign')")
    @Audit(action = "sign", resource = "dispatch_order")
    public R<WmsDeliveryOrder> sign(@PathVariable Long id, @Valid @RequestBody SignRequest req) {
        return R.ok(deliveryOrderService.sign(id, req));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('dispatch:order:cancel')")
    @Audit(action = "cancel", resource = "dispatch_order")
    public R<WmsDeliveryOrder> cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest req) {
        return R.ok(deliveryOrderService.cancel(id, req));
    }

    @PostMapping("/{id}/raise-exception")
    @PreAuthorize("hasAuthority('dispatch:order:update')")
    @Audit(action = "raise_exception", resource = "dispatch_order")
    public R<WmsDeliveryException> raiseException(@PathVariable Long id,
                                                  @Valid @RequestBody RaiseExceptionRequest req) {
        return R.ok(deliveryOrderService.raiseException(id, req));
    }

    @PostMapping("/auto-assign/{id}")
    @PreAuthorize("hasAuthority('dispatch:order:assign')")
    @Audit(action = "auto_assign", resource = "dispatch_order")
    public R<WmsDeliveryOrder> autoAssign(@PathVariable Long id) {
        return R.ok(dispatchEngine.autoAssign(id));
    }
}
