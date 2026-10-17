package com.lumen.finance.controller;

import com.lumen.common.api.R;
import com.lumen.common.security.SecurityUtil;
import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.dto.PaymentRecordDtos.CreateRequest;
import com.lumen.finance.dto.PaymentRecordDtos.ImportResult;
import com.lumen.finance.entity.BillingPaymentRecord;
import com.lumen.finance.service.PaymentRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 收 / 付款流水 REST 控制器 (Iteration 4.4, Task 1.7).
 *
 * <p>路径 {@code /api/v1/finance/payment-records}; 4 个端点 (list + get + create + import).
 * 权限按动作细粒度:
 * <ul>
 *   <li>{@code finance:payment:view} — list + get</li>
 *   <li>{@code finance:payment:edit} — create + import (CSV)</li>
 * </ul>
 *
 * <p>对应 {@link PaymentRecordService}. tenantId 从 {@link TenantContext} 取,
 * operatorId 从 {@link SecurityUtil} 取. CSV 导入结果 {@link ImportResult}
 * 含 totalRows / successCount / skippedCount / errors 4 个字段.
 */
@RestController
@RequestMapping("/api/v1/finance/payment-records")
@RequiredArgsConstructor
public class BillingPaymentRecordController {

    private final PaymentRecordService paymentRecordService;

    /**
     * 分页列出指定租户的收 / 付款流水 (可选 period / direction 过滤),
     * 按 payment_date desc, id desc 排序.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('finance:payment:view')")
    public R<List<BillingPaymentRecord>> list(
        @RequestParam(required = false) String period,
        @RequestParam(required = false) String direction,
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(defaultValue = "20") Integer size
    ) {
        Long tenantId = TenantContext.require();
        return R.ok(paymentRecordService.list(tenantId, period, direction, page, size));
    }

    /**
     * 按 id 取收 / 付款流水. 缺失 / 跨租户 → {@code PAYMENT_RECORD_NOT_FOUND}.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('finance:payment:view')")
    public R<BillingPaymentRecord> get(@PathVariable Long id) {
        Long tenantId = TenantContext.require();
        return R.ok(paymentRecordService.get(tenantId, id));
    }

    /**
     * 新增收 / 付款流水. 请求体见 {@link CreateRequest} —
     * period 必为 {@code YYYY-MM}; amount ≥ 0.01; currency 3 位 ISO 4217.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('finance:payment:edit')")
    public R<BillingPaymentRecord> create(@Valid @RequestBody CreateRequest req) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        return R.ok(paymentRecordService.create(tenantId, operatorId, req));
    }

    /**
     * CSV 批量导入指定期间的收 / 付款流水 (multipart/form-data).
     * 返回 {@link ImportResult}: 成功行 / 跳过行 / 错误明细.
     */
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('finance:payment:edit')")
    public R<ImportResult> importCsv(@RequestParam String period,
                                     @RequestParam MultipartFile file) {
        Long tenantId = TenantContext.require();
        Long operatorId = SecurityUtil.getCurrentUserId();
        return R.ok(paymentRecordService.importCsv(tenantId, period, operatorId, file));
    }
}