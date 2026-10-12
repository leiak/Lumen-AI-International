package com.lumen.dispatch.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest;
import com.lumen.dispatch.dto.DriverDtos.DriverVO;
import com.lumen.dispatch.dto.DriverDtos.QueryDriverRequest;
import com.lumen.dispatch.dto.DriverDtos.UpdateDriverRequest;
import com.lumen.dispatch.entity.WmsDriver;
import com.lumen.dispatch.service.DriverService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 司机主数据 REST 控制器.
 *
 * <p>状态机极简：{@code suspend} (ACTIVE→SUSPENDED) /
 * {@code resume} (SUSPENDED→ACTIVE) 直接 setStatus.
 */
@RestController
@RequestMapping("/api/v1/wms/drivers")
@RequiredArgsConstructor
public class DriverController {

    private final DriverService driverService;

    @GetMapping
    @PreAuthorize("hasAuthority('dispatch:driver:list')")
    public R<PageResult<WmsDriver>> page(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "20") long size,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String name) {
        QueryDriverRequest q = new QueryDriverRequest();
        q.setStatus(status);
        q.setName(name);
        return R.ok(driverService.page(current, Math.min(size, 200L), q));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch:driver:list')")
    public R<WmsDriver> get(@PathVariable Long id) {
        return R.ok(driverService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('dispatch:driver:create')")
    @Audit(action = "create", resource = "dispatch_driver")
    public R<WmsDriver> create(@Valid @RequestBody CreateDriverRequest req) {
        return R.ok(driverService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch:driver:update')")
    @Audit(action = "update", resource = "dispatch_driver", recordResponse = false)
    public R<WmsDriver> update(@PathVariable Long id, @Valid @RequestBody UpdateDriverRequest req) {
        return R.ok(driverService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch:driver:update')")
    @Audit(action = "delete", resource = "dispatch_driver")
    public R<Void> delete(@PathVariable Long id) {
        driverService.delete(id);
        return R.ok(null);
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('dispatch:driver:suspend')")
    @Audit(action = "suspend", resource = "dispatch_driver")
    public R<WmsDriver> suspend(@PathVariable Long id) {
        return R.ok(driverService.suspend(id));
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('dispatch:driver:suspend')")
    @Audit(action = "resume", resource = "dispatch_driver")
    public R<WmsDriver> resume(@PathVariable Long id) {
        return R.ok(driverService.resume(id));
    }

    /**
     * 实体转 VO（按需）— 当前 controller 主要返回 entity，VO 仅保留供
     * 后续扩展使用.
     */
    public DriverVO toVO(WmsDriver entity) {
        return driverService.toVO(entity);
    }
}