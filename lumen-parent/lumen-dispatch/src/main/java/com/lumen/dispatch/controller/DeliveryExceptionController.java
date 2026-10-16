package com.lumen.dispatch.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.CreateDeliveryExceptionRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.InvestigateRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.QueryDeliveryExceptionRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.ReopenRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.ResolveRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.UpdateDeliveryExceptionRequest;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.service.DeliveryExceptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 异常工单 REST 控制器 (Iteration 4.1 PR4 + 4.2 PR5).
 *
 * <p>前缀 {@code /api/v1/dispatch/exceptions};按 spec §10 权限守门.
 *
 * <p>4.2 PR5: 同时接受 {@code dispatch:exception:*} (旧 dispatch_admin) 与
 * {@code cs:exception:*} (新 cs_* 三角色) 两套 perm code — V25 把 dispatch_admin
 * 的 dispatch:exception:* 全量解绑, 异常工单处理权切到 cs_manager / cs_supervisor /
 * customer_service, 但 controller 不强制 caller 必须是新角色, 以便平滑迁移与兼容
 * 仍持有旧 perm code 的角色 (例如 SUPER_ADMIN 在 V21 拿到 dispatch:exception:*, 仍
 * 可调用).
 */
@RestController
@RequestMapping("/api/v1/dispatch/exceptions")
@RequiredArgsConstructor
public class DeliveryExceptionController {

    private final DeliveryExceptionService service;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('dispatch:exception:list', 'cs:exception:view')")
    public R<PageResult<WmsDeliveryException>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long orderId) {
        QueryDeliveryExceptionRequest q = new QueryDeliveryExceptionRequest();
        q.setStatus(status);
        q.setType(type);
        q.setOrderId(orderId);
        return R.ok(service.page(current, Math.min(size, 200L), q));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('dispatch:exception:list', 'cs:exception:view')")
    public R<WmsDeliveryException> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('dispatch:exception:create', 'cs:exception:respond')")
    @Audit(action = "create", resource = "dispatch_exception")
    public R<WmsDeliveryException> create(@Valid @RequestBody CreateDeliveryExceptionRequest req) {
        return R.ok(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('dispatch:exception:create', 'cs:exception:respond')")
    @Audit(action = "update", resource = "dispatch_exception", recordResponse = false)
    public R<WmsDeliveryException> update(@PathVariable Long id,
                                          @Valid @RequestBody UpdateDeliveryExceptionRequest req) {
        return R.ok(service.update(id, req));
    }

    @PostMapping("/{id}/investigate")
    @PreAuthorize("hasAnyAuthority('dispatch:exception:investigate', 'cs:exception:respond')")
    @Audit(action = "investigate", resource = "dispatch_exception")
    public R<WmsDeliveryException> investigate(@PathVariable Long id,
                                               @Valid @RequestBody InvestigateRequest req) {
        return R.ok(service.investigate(id, req));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyAuthority('dispatch:exception:resolve', 'cs:exception:respond')")
    @Audit(action = "resolve", resource = "dispatch_exception")
    public R<WmsDeliveryException> resolve(@PathVariable Long id,
                                           @Valid @RequestBody ResolveRequest req) {
        return R.ok(service.resolve(id, req));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyAuthority('dispatch:exception:close', 'cs:exception:respond')")
    @Audit(action = "close", resource = "dispatch_exception")
    public R<WmsDeliveryException> close(@PathVariable Long id) {
        return R.ok(service.close(id));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyAuthority('dispatch:exception:investigate', 'cs:exception:respond')")
    @Audit(action = "reopen", resource = "dispatch_exception")
    public R<WmsDeliveryException> reopen(@PathVariable Long id,
                                          @Valid @RequestBody ReopenRequest req) {
        return R.ok(service.reopen(id, req));
    }
}
