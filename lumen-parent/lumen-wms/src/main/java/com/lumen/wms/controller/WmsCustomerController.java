package com.lumen.wms.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.wms.dto.CreateCustomerRequest;
import com.lumen.wms.dto.UpdateCustomerRequest;
import com.lumen.wms.entity.WmsCustomer;
import com.lumen.wms.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 客户主数据 REST 控制器.
 */
@RestController
@RequestMapping("/api/v1/wms/customers")
@RequiredArgsConstructor
public class WmsCustomerController {

    private final CustomerService customerService;

    @GetMapping
    @PreAuthorize("hasAuthority('wms:customer:list')")
    public R<PageResult<WmsCustomer>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(customerService.page(pageNum, pageSize, keyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:customer:list')")
    public R<WmsCustomer> getById(@PathVariable Long id) {
        return R.ok(customerService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:customer:create')")
    @Audit(action = "create", resource = "wms_customer")
    public R<WmsCustomer> create(@Valid @RequestBody CreateCustomerRequest req) {
        return R.ok(customerService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:customer:update')")
    @Audit(action = "update", resource = "wms_customer", recordResponse = false)
    public R<WmsCustomer> update(@PathVariable Long id, @Valid @RequestBody UpdateCustomerRequest req) {
        return R.ok(customerService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:customer:delete')")
    @Audit(action = "delete", resource = "wms_customer")
    public R<Void> delete(@PathVariable Long id) {
        customerService.delete(id);
        return R.ok(null);
    }
}