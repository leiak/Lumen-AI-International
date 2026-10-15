package com.lumen.cs.controller;

import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.dto.SlaConfigDtos.CreateRequest;
import com.lumen.cs.dto.SlaConfigDtos.GetResponse;
import com.lumen.cs.dto.SlaConfigDtos.ListRequest;
import com.lumen.cs.dto.SlaConfigDtos.ListResponse;
import com.lumen.cs.dto.SlaConfigDtos.UpdateRequest;
import com.lumen.cs.service.SlaConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SLA 配置 REST 控制器 (Iteration 4.2 PR2).
 *
 * <p>权限细粒度到 {@code cs:sla:config}. 该权限由 PR5 V25 引入；
 * PR2 阶段 @PreAuthorize 已就位，HTTP 集成测试覆盖待 PR5 落地后进行.
 */
@RestController
@RequestMapping("/api/v1/cs/sla-configs")
@RequiredArgsConstructor
public class SlaConfigController {

    private final SlaConfigService service;

    @GetMapping
    @PreAuthorize("hasAuthority('cs:sla:config')")
    public R<ListResponse> list(@RequestParam(required = false) String severity,
                                @RequestParam(required = false) String level,
                                @RequestParam(required = false, defaultValue = "1") Integer page,
                                @RequestParam(required = false, defaultValue = "20") Integer size) {
        ListRequest req = new ListRequest(severity, level, page, size);
        return R.ok(service.list(TenantContext.require(), req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('cs:sla:config')")
    public R<GetResponse> get(@PathVariable Long id) {
        return R.ok(service.get(TenantContext.require(), id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('cs:sla:config')")
    public R<GetResponse> create(@Valid @RequestBody CreateRequest req) {
        return R.ok(service.create(TenantContext.require(), req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('cs:sla:config')")
    public R<GetResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest req) {
        return R.ok(service.update(TenantContext.require(), id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('cs:sla:config')")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(TenantContext.require(), id);
        return R.ok(null);
    }
}