package com.lumen.cs.controller;

import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.dto.HolidayDtos.CreateRequest;
import com.lumen.cs.dto.HolidayDtos.GetResponse;
import com.lumen.cs.dto.HolidayDtos.ListRequest;
import com.lumen.cs.dto.HolidayDtos.ListResponse;
import com.lumen.cs.dto.HolidayDtos.UpdateRequest;
import com.lumen.cs.service.HolidayService;
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
 * 节假日 REST 控制器 (Iteration 4.2 PR2).
 *
 * <p>权限细粒度到 {@code cs:holiday:config}.
 */
@RestController
@RequestMapping("/api/v1/cs/holidays")
@RequiredArgsConstructor
public class HolidayController {

    private final HolidayService service;

    @GetMapping
    @PreAuthorize("hasAuthority('cs:holiday:config')")
    public R<ListResponse> list(@RequestParam(required = false, defaultValue = "1") Integer page,
                                @RequestParam(required = false, defaultValue = "200") Integer size) {
        return R.ok(service.list(TenantContext.require(), new ListRequest(page, size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('cs:holiday:config')")
    public R<GetResponse> get(@PathVariable Long id) {
        return R.ok(service.get(TenantContext.require(), id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('cs:holiday:config')")
    public R<GetResponse> create(@Valid @RequestBody CreateRequest req) {
        return R.ok(service.create(TenantContext.require(), req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('cs:holiday:config')")
    public R<GetResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest req) {
        return R.ok(service.update(TenantContext.require(), id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('cs:holiday:config')")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(TenantContext.require(), id);
        return R.ok(null);
    }
}