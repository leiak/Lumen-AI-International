package com.lumen.finance.controller;

import com.lumen.common.api.R;
import com.lumen.common.security.SecurityUtil;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.dto.BillingAccrualDtos.View;
import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.service.AccrualService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 暂估单 REST 控制器 (Iteration 4.3.1, PR3-T3.5).
 *
 * <p>路径 {@code /api/v1/finance/accruals}; 4 个端点 (list + get + finalize + release).
 * 权限按动作细粒度:
 * <ul>
 *   <li>{@code finance:accrual:view} — list + get</li>
 *   <li>{@code finance:accrual:finalize} — finalize (OPEN → LOCKED → CONVERTED + 生成 invoice)</li>
 *   <li>{@code finance:accrual:release} — release (OPEN → RELEASED)</li>
 * </ul>
 *
 * <p>对应 V27 RBAC seed. tenantId 从 {@link TenantContext} 取, operatorId 从 {@link SecurityUtil} 取.
 */
@RestController
@RequestMapping("/api/v1/finance/accruals")
@RequiredArgsConstructor
public class BillingAccrualController {

    private final AccrualService accrualService;

    @GetMapping
    @PreAuthorize("hasAuthority('finance:accrual:view')")
    public R<List<View>> list(@RequestParam(required = false) String businessType,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false, defaultValue = "1") Integer page,
                              @RequestParam(required = false, defaultValue = "20") Integer size) {
        Long tenantId = TenantContext.require();
        List<BillingAccrual> entities = accrualService.list(tenantId, businessType, status, page, size);
        return R.ok(entities.stream().map(BillingAccrualController::toView).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('finance:accrual:view')")
    public R<View> get(@PathVariable Long id) {
        Long tenantId = TenantContext.require();
        return R.ok(toView(accrualService.get(tenantId, id)));
    }

    /**
     * 终态化: OPEN → LOCKED → 生成 invoice (DRAFT) → CONVERTED.
     * 直接返回终态化后的 accrual (单次 DB 写入, 不再二次 fetch); 新生成的 invoice 客户端
     * 通过 {@code GET /api/v1/finance/invoices/{id}} 单独查 (service 不暴露 invoice 引用).
     */
    @PostMapping("/{id}/finalize")
    @PreAuthorize("hasAuthority('finance:accrual:finalize')")
    public R<View> finalize(@PathVariable Long id) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        BillingAccrual acc = accrualService.finalize(tenantId, id, operatorId);
        return R.ok(toView(acc));
    }

    /**
     * 释放: OPEN → RELEASED, 写入 {@code errorMsg=reason} 留痕.
     * 请求体 {@code {"reason": "..."}} 可选, 不传默认 "released".
     */
    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('finance:accrual:release')")
    public R<View> release(@PathVariable Long id,
                           @RequestBody(required = false) Map<String, String> body) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        String reason = body == null ? null : body.get("reason");
        return R.ok(toView(accrualService.release(tenantId, id, reason, operatorId)));
    }

    // ---------- mapper helper ----------

    private static View toView(BillingAccrual a) {
        if (a == null) return null;
        return new View(
            a.getId(),
            a.getTenantId(),
            a.getSourceEventId(),
            a.getBusinessType(),
            a.getBusinessId(),
            a.getRuleId(),
            a.getDirection(),
            a.getCounterpartyId(),
            a.getGrossAmount(),
            a.getTaxAmount(),
            a.getNetAmount(),
            a.getCurrency(),
            a.getStatus(),
            a.getErrorMsg(),
            a.getLockedAt(),
            a.getCreatedAt()
        );
    }
}