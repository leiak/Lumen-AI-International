package com.lumen.cs.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.dto.EscalationDtos.ExceptionSlaView;
import com.lumen.cs.entity.WmsDeliveryExceptionView;
import com.lumen.cs.mapper.WmsDeliveryExceptionViewMapper;
import com.lumen.cs.service.impl.ExceptionSlaAggregationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * CS 客服视角异常工单列表 REST 控制器 (Iteration 4.2 PR6 finalize).
 *
 * <p>前缀 {@code /api/v1/cs/exceptions}; 用于 CS 客服工作台
 * (/cs/exceptions) 的 SLA 视角列表.
 *
 * <p><b>实现说明 (PR6 finalize 后):</b>
 * <ul>
 *   <li>{@link WmsDeliveryExceptionView} 原始行经 {@link ExceptionSlaAggregationService}
 *       聚合成 {@link ExceptionSlaView} (severity / currentLevel / businessMinutesElapsed /
 *       resolveTargetMinutes / escalationStatus / slaEvents), 与 FE {@code ExceptionSlaView}
 *       接口对齐 (Lumen-services/cs/exceptionView.ts).</li>
 *   <li>分页参数命名 {@code current} / {@code size}, 与
 *       {@code DeliveryExceptionController} 保持一致 (FE 锁定的契约).</li>
 *   <li>{@code severity} 过滤接受 FE 发送的 {@code HIGH / MEDIUM / LOW}; 通过
 *       {@link ExceptionSlaAggregationService#typesForSeverity} 反向映射成
 *       {@code wms_delivery_exception.type} 集合走 {@code .in(...)}.</li>
 *   <li>权限守门简化为 {@code hasAuthority('cs:exception:view')} (V25 seed perm id 127),
 *       与 {@link com.lumen.cs.controller.SlaConfigController} 风格一致.</li>
 * </ul>
 *
 * <p><b>注意:</b> 与 lumen-dispatch 的
 * {@link com.lumen.dispatch.controller.DeliveryExceptionController}
 * 同源 — 后者面向 dispatch_admin / 老 perm, 本 controller 是 CS 视角 SLA 视图,
 * 走 {@code cs_manager / cs_supervisor / customer_service} (V25 3 角色) 单一 perm code
 * 即可访问.
 */
@RestController
@RequestMapping("/api/v1/cs/exceptions")
@RequiredArgsConstructor
public class CsExceptionController {

    private final WmsDeliveryExceptionViewMapper mapper;
    private final ExceptionSlaAggregationService aggregator;

    /**
     * GET /api/v1/cs/exceptions — 分页查询异常工单 (CS SLA 视角).
     *
     * @param current  当前页 (1-based, default 1). 与 FE / Spring {@code DeliveryExceptionController} 同名.
     * @param size     每页条数 (default 20, 上限 200).
     * @param severity 可选, 按 severity 过滤 ({@code HIGH / MEDIUM / LOW}); 反向映射到对应的
     *                 {@code wms_delivery_exception.type} 集合走 mapper {@code .in(...)}.
     *                 未知值 → 空结果.
     * @param status   可选, 按工单状态过滤
     *                 ({@code OPEN / INVESTIGATING / RESOLVED / CLOSED / CANCELLED}).
     * @return paged {@link ExceptionSlaView} list (FE 渲染字段: 当前 Level / 倒计时 / 处理人 / 最新事件时间)
     */
    @GetMapping
    @PreAuthorize("hasAuthority('cs:exception:view')")
    public R<PageResult<ExceptionSlaView>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status) {

        long effectiveSize = Math.min(size, 200L);
        Long tenantId = TenantContext.require();

        // severity filter 翻译: 未知 severity 短路返空 (不进 mapper)
        Set<String> severityTypes = ExceptionSlaAggregationService.typesForSeverity(severity);
        if (severity != null && !severity.isBlank() && severityTypes.isEmpty()) {
            // FE 传了无法识别的 severity (例如 "CRITICAL"); 不应爆 500, 直接返空.
            return R.ok(PageResult.of(List.of(), 0, current, effectiveSize));
        }

        Page<WmsDeliveryExceptionView> page = new Page<>(current, effectiveSize);
        LambdaQueryWrapper<WmsDeliveryExceptionView> q = new LambdaQueryWrapper<WmsDeliveryExceptionView>()
            .eq(WmsDeliveryExceptionView::getTenantId, tenantId)
            .orderByDesc(WmsDeliveryExceptionView::getCreatedAt);
        if (!severityTypes.isEmpty()) {
            q.in(WmsDeliveryExceptionView::getType, severityTypes);
        }
        if (status != null && !status.isBlank()) {
            q.eq(WmsDeliveryExceptionView::getStatus, status);
        }

        Page<WmsDeliveryExceptionView> result = mapper.selectPage(page, q);
        List<ExceptionSlaView> views = result.getRecords().stream()
            .map(aggregator::toView)
            .toList();
        return R.ok(PageResult.of(views, result.getTotal(), current, effectiveSize));
    }
}
