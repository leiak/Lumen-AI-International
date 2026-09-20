package com.lumen.common.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SysAuditLogController — 审计日志查询端点。
 *
 * <p>{@link AuditLogAspect} 通过 {@code @Audit} 注解写入 {@link AuditLog} 行，
 * 但写完后没人能查。本 controller 提供分页 + 过滤的查询能力 —— 之前前端
 * AuditLog 页面只能显示 "Audit log query endpoint not yet implemented"
 * 空状态（{@code lumen-admin-web/src/pages/AuditLog/index.tsx}）。
 *
 * <p>租户隔离：MyBatis-Plus {@code TenantLineInnerInterceptor} 会自动按
 * {@link TenantContext} 拼 {@code AND tenant_id = ?}，所以这里只需调用
 * mapper 的标准 API，无需手写 tenant_id 条件。
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class SysAuditLogController {

    private final AuditLogMapper auditLogMapper;

    @GetMapping
    @PreAuthorize("hasAuthority('audit:list')")
    public R<PageResult<AuditLog>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String resource) {

        Page<AuditLog> pageReq = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<AuditLog> q = new LambdaQueryWrapper<>();
        if (status != null) {
            q.eq(AuditLog::getStatus, status);
        }
        if (resource != null && !resource.isBlank()) {
            q.eq(AuditLog::getResource, resource);
        }
        if (keyword != null && !keyword.isBlank()) {
            // 简单 LIKE：traceId / username / action / uri 任意包含关键字
            q.and(w -> w.like(AuditLog::getTraceId, keyword)
                    .or().like(AuditLog::getUsername, keyword)
                    .or().like(AuditLog::getAction, keyword)
                    .or().like(AuditLog::getUri, keyword));
        }
        q.orderByDesc(AuditLog::getId);

        Page<AuditLog> result = auditLogMapper.selectPage(pageReq, q);
        return R.ok(PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize));
    }
}