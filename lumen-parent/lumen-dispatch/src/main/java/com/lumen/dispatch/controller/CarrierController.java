package com.lumen.dispatch.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.dispatch.dto.CarrierDtos.CarrierVO;
import com.lumen.dispatch.dto.CarrierDtos.CreateCarrierRequest;
import com.lumen.dispatch.dto.CarrierDtos.QueryCarrierRequest;
import com.lumen.dispatch.dto.CarrierDtos.UpdateCarrierRequest;
import com.lumen.dispatch.entity.WmsCarrier;
import com.lumen.dispatch.service.CarrierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 承运人主数据 REST 控制器.
 *
 * <p>承运人不参与状态机流转，仅暴露 CRUD；权限细粒度到
 * {@code dispatch:carrier:list / create / update}.
 */
@RestController
@RequestMapping("/api/v1/wms/carriers")
@RequiredArgsConstructor
public class CarrierController {

    private final CarrierService carrierService;

    @GetMapping
    @PreAuthorize("hasAuthority('dispatch:carrier:list')")
    public R<PageResult<WmsCarrier>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String code,
                                          @RequestParam(required = false) String name) {
        QueryCarrierRequest q = new QueryCarrierRequest();
        q.setStatus(status);
        q.setCode(code);
        q.setName(name);
        return R.ok(carrierService.page(current, Math.min(size, 200L), q));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch:carrier:list')")
    public R<WmsCarrier> get(@PathVariable Long id) {
        return R.ok(carrierService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('dispatch:carrier:create')")
    @Audit(action = "create", resource = "dispatch_carrier")
    public R<WmsCarrier> create(@Valid @RequestBody CreateCarrierRequest req) {
        return R.ok(carrierService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch:carrier:update')")
    @Audit(action = "update", resource = "dispatch_carrier", recordResponse = false)
    public R<WmsCarrier> update(@PathVariable Long id, @Valid @RequestBody UpdateCarrierRequest req) {
        return R.ok(carrierService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch:carrier:update')")
    @Audit(action = "delete", resource = "dispatch_carrier")
    public R<Void> delete(@PathVariable Long id) {
        carrierService.delete(id);
        return R.ok(null);
    }

    public CarrierVO toVO(WmsCarrier entity) {
        return carrierService.toVO(entity);
    }
}