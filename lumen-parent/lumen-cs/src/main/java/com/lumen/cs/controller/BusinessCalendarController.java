package com.lumen.cs.controller;

import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.dto.CalendarDtos.CreateRequest;
import com.lumen.cs.dto.CalendarDtos.GetResponse;
import com.lumen.cs.dto.CalendarDtos.ListResponse;
import com.lumen.cs.dto.CalendarDtos.UpdateRequest;
import com.lumen.cs.service.BusinessCalendarService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务时间 REST 控制器 (Iteration 4.2 PR2).
 *
 * <p>权限细粒度到 {@code cs:calendar:config}.
 */
@RestController
@RequestMapping("/api/v1/cs/calendar")
@RequiredArgsConstructor
public class BusinessCalendarController {

    private final BusinessCalendarService service;

    @GetMapping
    @PreAuthorize("hasAuthority('cs:calendar:config')")
    public R<ListResponse> list() {
        return R.ok(service.list(TenantContext.get()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('cs:calendar:config')")
    public R<GetResponse> get(@PathVariable Long id) {
        return R.ok(service.get(TenantContext.get(), id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('cs:calendar:config')")
    public R<GetResponse> create(@Valid @RequestBody CreateRequest req) {
        return R.ok(service.create(TenantContext.get(), req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('cs:calendar:config')")
    public R<GetResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest req) {
        return R.ok(service.update(TenantContext.get(), id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('cs:calendar:config')")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(TenantContext.get(), id);
        return R.ok(null);
    }
}